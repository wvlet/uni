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
  * Replays the cases generated from the Lean model in `spec/lean/UniSpec/Rx.lean` through
  * `RxRunner`. Each input is fed into `Rx.queue`, which accepts any event sequence and delivers
  * queued events synchronously, so the cases run on every platform.
  */
class RxSpecTest extends UniTest:

  private case class SpecCase(input: String, ops: String, expected: String)

  private val specCases: Seq[SpecCase] = RxSpecCases
    .cases
    .map { line =>
      line.split('|').map(_.trim) match
        case Array(input, ops, expected) =>
          SpecCase(input, ops, expected)
        case _ =>
          throw IllegalArgumentException(s"Invalid case: ${line}")
    }

  private def tokens(s: String): Seq[String] =
    if s == "-" then
      Seq.empty
    else
      s.split(' ').toSeq

  private def applyOp(rx: Rx[Int], op: String): Rx[Int] =
    op match
      case "even" =>
        rx.filter(_ % 2 == 0)
      case "inc" =>
        rx.map(_ + 1)
      case s if s.startsWith("take:") =>
        rx.take(s.stripPrefix("take:").toLong)
      case other =>
        throw IllegalArgumentException(s"Unknown operator: ${other}")

  private def run(input: String, ops: String): String =
    val queue = Rx.queue[Int]()
    tokens(input).foreach {
      case "C" =>
        queue.add(OnCompletion)
      case "E" =>
        queue.add(OnError(IllegalStateException("spec failure")))
      case v =>
        queue.add(OnNext(v.toInt))
    }
    val rx       = tokens(ops).foldLeft(queue: Rx[Int])(applyOp)
    val observed = mutable.ArrayBuffer.empty[String]
    val c        =
      RxRunner.run(rx) {
        case OnNext(v) =>
          observed += v.toString
        case OnCompletion =>
          observed += "C"
        case OnFailure(_) =>
          observed += "E"
      }
    c.cancel
    if observed.isEmpty then
      "-"
    else
      observed.mkString(" ")

  test("load the generated cases") {
    specCases.nonEmpty shouldBe true
  }

  specCases
    .groupBy(_.ops)
    .toSeq
    .sortBy(_._1)
    .foreach { (ops, cases) =>
      test(s"pipeline: ${ops}") {
        val actual = cases.map(c => s"${c.input} => ${run(c.input, c.ops)}")
        actual shouldBe cases.map(c => s"${c.input} => ${c.expected}")
      }
    }

end RxSpecTest
