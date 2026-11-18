/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.rx

import wvlet.uni.test.UniTest

import scala.collection.mutable

/**
  * Replays the cases generated from the Lean model in `spec/lean/UniSpec/RxRecover.lean`: each
  * input is a pre-filled `Rx.queue`, run with `RxRunner.run` (default mode) or
  * `RxRunner.runContinuously` (continuous mode).
  */
class RxRecoverSpecTest extends UniTest:

  private case class SpecCase(mode: String, input: String, ops: String, expected: String)

  private val specCases: Seq[SpecCase] = RxRecoverSpecCases
    .cases
    .map { line =>
      line.split('|').map(_.trim) match
        case Array(mode, input, ops, expected) =>
          SpecCase(mode, input, ops, expected)
        case _ =>
          throw IllegalArgumentException(s"Invalid case: ${line}")
    }

  private def tokens(s: String): Seq[String] =
    if s == "-" then
      Seq.empty
    else
      s.split(' ').toSeq

  private def applyOp(rx: Rx[Int], op: String): Rx[Int] =
    op.split(':') match
      case Array("even") =>
        rx.filter(_ % 2 == 0)
      case Array("inc") =>
        rx.map(_ + 1)
      case Array("take", n) =>
        rx.take(n.toLong)
      case Array("boom", k) =>
        rx.map { v =>
          if v == k.toInt then
            throw IllegalStateException(s"boom on ${v}")
          v
        }
      case Array("rec", r) =>
        rx.recover { case _: Throwable =>
          r.toInt
        }
      case _ =>
        throw IllegalArgumentException(s"Unknown operator: ${op}")

  private def run(c: SpecCase): String =
    val queue = Rx.queue[Int]()
    tokens(c.input).foreach {
      case "C" =>
        queue.add(OnCompletion)
      case "E" =>
        queue.add(OnError(IllegalStateException("source failure")))
      case v =>
        queue.add(OnNext(v.toInt))
    }
    val rx                      = tokens(c.ops).foldLeft(queue: Rx[Int])(applyOp)
    val observed                = mutable.ArrayBuffer.empty[String]
    val effect: RxEvent => Unit = {
      case OnNext(v) =>
        observed += v.toString
      case OnCompletion =>
        observed += "C"
      case OnError(_) =>
        observed += "E"
    }
    val cancel =
      c.mode match
        case "default" =>
          RxRunner.run(rx)(effect)
        case "continuous" =>
          RxRunner.runContinuously(rx)(effect)
        case other =>
          throw IllegalArgumentException(s"Unknown mode: ${other}")
    cancel.cancel
    if observed.isEmpty then
      "-"
    else
      observed.mkString(" ")

  end run

  test("load the generated cases") {
    specCases.nonEmpty shouldBe true
  }

  specCases
    .groupBy(c => s"${c.mode}: ${c.ops}")
    .toSeq
    .sortBy(_._1)
    .foreach { (name, cases) =>
      test(name) {
        val actual = cases.map(c => s"${c.input} => ${run(c)}")
        actual shouldBe cases.map(c => s"${c.input} => ${c.expected}")
      }
    }

end RxRecoverSpecTest
