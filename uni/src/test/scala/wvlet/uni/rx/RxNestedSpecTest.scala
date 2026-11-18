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
  * Replays the cases generated from the Lean model in `spec/lean/UniSpec/RxNested.lean` through
  * `RxRunner`. Every source is a pre-filled `Rx.queue`, which delivers its events synchronously, so
  * the expected order is deterministic on every platform.
  */
class RxNestedSpecTest extends UniTest:

  private case class SpecCase(op: String, sources: String, expected: String)

  private val specCases: Seq[SpecCase] = RxNestedSpecCases
    .cases
    .map { line =>
      line.split('|').map(_.trim) match
        case Array(op, sources, expected) =>
          SpecCase(op, sources, expected)
        case _ =>
          throw IllegalArgumentException(s"Invalid case: ${line}")
    }

  private def events(trace: String): Seq[RxEvent] =
    if trace == "-" then
      Seq.empty
    else
      trace
        .split(' ')
        .toSeq
        .map {
          case "C" =>
            OnCompletion
          case "E" =>
            OnError(IllegalStateException("spec failure"))
          case v =>
            OnNext(v.toInt)
        }

  private def source(es: Seq[RxEvent]): Rx[Int] =
    val queue = Rx.queue[Int]()
    es.foreach(queue.add)
    queue

  // The nested streams of flatMap, as defined in GenRxNestedCases.lean
  private def nested(name: String)(x: Int): Rx[Int] =
    name match
      case "dup" =>
        source(Seq(OnNext(x), OnNext(x * 10), OnCompletion))
      case "open" =>
        source(Seq(OnNext(x)))
      case "failOdd" =>
        source(
          Seq(
            OnNext(x),
            if x % 2 == 1 then
              OnError(IllegalStateException("odd"))
            else
              OnCompletion
          )
        )
      case "empty" =>
        source(Seq(OnCompletion))
      case other =>
        throw IllegalArgumentException(s"Unknown nested stream: ${other}")

  private def build(c: SpecCase): Rx[Int] =
    val sources =
      if c.sources.isEmpty then
        Seq.empty
      else
        c.sources.split(" / ").toSeq.map(t => source(events(t)))
    c.op match
      case "concat" =>
        sources(0).concat(sources(1))
      case "merge" =>
        // MergeOp directly, so even 0 and 1 sources go through RxRunner's merge
        Rx.MergeOp(sources)
      case op if op.startsWith("flatMap:") =>
        sources(0).flatMap(nested(op.stripPrefix("flatMap:")))
      case other =>
        throw IllegalArgumentException(s"Unknown operator: ${other}")

  private def run(c: SpecCase): String =
    val observed = mutable.ArrayBuffer.empty[String]
    val cancel   =
      RxRunner.run(build(c)) {
        case OnNext(v) =>
          observed += v.toString
        case OnCompletion =>
          observed += "C"
        case OnError(_) =>
          observed += "E"
      }
    cancel.cancel
    if observed.isEmpty then
      "-"
    else
      observed.mkString(" ")

  test("load the generated cases") {
    specCases.nonEmpty shouldBe true
  }

  specCases
    .groupBy(_.op)
    .toSeq
    .sortBy(_._1)
    .foreach { (op, cases) =>
      test(op) {
        val actual = cases.map(c => s"${c.sources} => ${run(c)}")
        actual shouldBe cases.map(c => s"${c.sources} => ${c.expected}")
      }
    }

end RxNestedSpecTest
