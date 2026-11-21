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
import wvlet.uni.util.Result

import scala.collection.mutable

/**
  * Replays the cases generated from the target event model in `spec/lean/UniSpec/RxR3.lean` and
  * `RxR3Ops.lean`: `OnError` never ends a stream, and `OnCompletion` (success or failure) is the
  * only end. Each input is a pre-filled `Rx.queue`, run with `RxRunner.runContinuously`.
  */
class RxR3SpecTest extends UniTest:

  private case class SpecCase(input: String, ops: String, expected: String)

  private val specCases: Seq[SpecCase] = RxR3SpecCases
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

  private def source(trace: String): Rx[Int] =
    val queue = Rx.queue[Int]()
    tokens(trace).foreach {
      case "C" =>
        queue.add(OnCompletion.success)
      case "F" =>
        queue.add(OnCompletion.failure(IllegalStateException("source failure")))
      case "E" =>
        queue.add(OnError(IllegalStateException("source error")))
      case v =>
        queue.add(OnNext(v.toInt))
    }
    queue

  // The nested streams of flatMap, as defined in GenRxR3Cases.lean
  private def nested(name: String)(x: Int): Rx[Int] =
    name match
      case "dup" =>
        source(s"${x} ${x * 10} C")
      case "fail" =>
        source(s"${x} F")
      case "resume" =>
        source(s"${x} E ${x * 10} C")
      case "live" =>
        source(s"${x}")
      case other =>
        throw IllegalArgumentException(s"Unknown nested stream: ${other}")

  // The recovery streams of recoverWith, as defined in GenRxR3Cases.lean
  private def recovery(name: String): Rx[Int] =
    name match
      case "done" =>
        source("0 C")
      case "fail" =>
        source("0 F")
      case "live" =>
        source("0")
      case other =>
        throw IllegalArgumentException(s"Unknown recovery stream: ${other}")

  private def applyOp(rx: Rx[Int], op: String): Rx[Int] =
    op.split(':') match
      case Array("flatMap", name) =>
        rx.flatMap(nested(name))
      case Array("recoverWith", name) =>
        rx.recoverWith { case _ =>
          recovery(name)
        }
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
        rx.recover { case _ =>
          r.toInt
        }
      case _ =>
        throw IllegalArgumentException(s"Unknown operator: ${op}")

  private def run(c: SpecCase): String =
    val rx       = tokens(c.ops).foldLeft(source(c.input))(applyOp)
    val observed = mutable.ArrayBuffer.empty[String]
    val cancel   =
      RxRunner.runContinuously(rx) {
        case OnNext(v) =>
          observed += v.toString
        case OnError(_) =>
          observed += "E"
        case OnCompletion(Result.Success(_)) =>
          observed += "C"
        case OnCompletion(Result.Failure(_)) =>
          observed += "F"
      }
    cancel.cancel
    if observed.isEmpty then
      "-"
    else
      observed.mkString(" ")

  end run

  test("replay the cases of the R3 event model") {
    val failures = specCases.flatMap { c =>
      val actual = run(c)
      Option.when(actual != c.expected)(
        s"${c.input} | ${c.ops} | expected ${c.expected}, got ${actual}"
      )
    }
    if failures.nonEmpty then
      fail(s"${failures.size} of ${specCases.size} cases differ:\n${failures.mkString("\n")}")
  }

end RxR3SpecTest
