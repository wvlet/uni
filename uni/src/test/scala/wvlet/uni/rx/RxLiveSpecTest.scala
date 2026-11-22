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
import scala.util.Failure
import scala.util.Success

/**
  * Replays the cases generated from the Lean model in `spec/lean/UniSpec/RxLive.lean` through
  * `RxRunner`. Each case drives `RxVar`s with a script of actions. `RxVar` delivers to its
  * subscribers before an update returns, so the recorded order is deterministic on every platform.
  */
class RxLiveSpecTest extends UniTest:

  private case class SpecCase(op: String, script: String, expected: String)

  private val specCases: Seq[SpecCase] = RxLiveSpecCases
    .cases
    .map { line =>
      line.split('|').map(_.trim) match
        case Array(op, script, expected) =>
          SpecCase(op, script, expected)
        case _ =>
          throw IllegalArgumentException(s"Invalid case: ${line}")
    }

  private def actions(script: String): Seq[String] =
    if script == "-" then
      Seq.empty
    else
      script.split(", ").toSeq

  private def failure = IllegalStateException("spec failure")

  /**
    * Records what the sink receives. It answers Continue to an error and Stop to the end, as
    * runContinuously does.
    */
  private class Sink:
    val records = mutable.ArrayBuffer.empty[String]

    def apply(ev: RxEvent): RxResult =
      ev match
        case OnNext(v) =>
          onValue(v)
          RxResult.Continue
        case OnError(_) =>
          records += "E"
          RxResult.Continue
        case OnCompletion =>
          records += "C"
          RxResult.Stop
        case OnFailure(_) =>
          records += "E"
          RxResult.Stop

    protected def onValue(v: Any): Unit =
      records += (
        v match
          case (a, b) =>
            s"(${a},${b})"
          case other =>
            other.toString
      )

    def result: String =
      if records.isEmpty then
        "-"
      else
        records.mkString(" ")

  end Sink

  /**
    * Var 0 starts with 1, and var i with i * 10, as `initVars` in RxLive.lean
    */
  private def newVars(n: Int): IndexedSeq[RxVar[Int]] = (0 until n).map { i =>
    Rx.variable(
      if i == 0 then
        1
      else
        i * 10
    )
  }

  private val runner = RxRunner()

  private def runScript(script: String, vars: IndexedSeq[RxVar[Int]], cancel: () => Unit): Unit =
    actions(script).foreach { a =>
      a.split(' ') match
        case Array("set", i, v) =>
          vars(i.toInt) := v.toInt
        case Array("stop", i) =>
          vars(i.toInt).stop()
        case Array("fail", i) =>
          vars(i.toInt).setException(failure)
        case Array("cancel") =>
          cancel()
        case _ =>
          throw IllegalArgumentException(s"Unknown action: ${a}")
    }

  // flatMap and transformRx map 1 to var 1, 2 to var 2, and throw on any other value
  private def target(vars: IndexedSeq[RxVar[Int]])(x: Int): Rx[Int] =
    x match
      case 1 | 2 =>
        vars(x)
      case _ =>
        throw IllegalArgumentException(s"no nested stream for ${x}")

  private def runSwitch(op: String, script: String): String =
    val vars = newVars(3)
    val rx   =
      op match
        case "flatMap" =>
          vars(0).flatMap(target(vars))
        case _ =>
          vars(0).transformRx {
            case Success(x) =>
              target(vars)(x)
            case Failure(_) =>
              vars(2)
          }
    val sink = Sink()
    val c    = runner.run(rx)(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  // The input throws without ending the var: on 0 an error recovered with var 1, on 3 an error the
  // function rejects, and on 4 an error on which it throws. The var's own failure recovers with var 2.
  private def runRecoverWith(op: String, script: String): String =
    val vars = newVars(3)
    val rx   = vars(0)
      .map { x =>
        x match
          case 0 =>
            throw IllegalArgumentException("recovered")
          case 3 =>
            throw ArithmeticException("rejected")
          case 4 =>
            throw UnsupportedOperationException("recovery throws")
          case _ =>
            x
      }
      .recoverWith {
        case _: IllegalArgumentException =>
          vars(1)
        case _: IllegalStateException =>
          vars(2)
        case _: UnsupportedOperationException =>
          throw IllegalStateException("recovery failed")
      }
    val sink = Sink()
    val c    = runner.run(rx)(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  private def runMerge(op: String, script: String): String =
    val vars = newVars(2)
    val sink = Sink()
    val c    = runner.run(Rx.MergeOp(Seq(vars(0), vars(1))))(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  // parFlatMap maps 1, 2, and 3 to vars 1, 2, and 3, and throws on any other value
  private def runParFlatMap(op: String, script: String): String =
    val n    = op.stripPrefix("parFlatMap:").toInt
    val vars = newVars(4)
    val rx   =
      vars(0).parFlatMap(n) { x =>
        x match
          case 1 | 2 | 3 =>
            vars(x)
          case _ =>
            throw IllegalArgumentException(s"no nested stream for ${x}")
      }
    val sink = Sink()
    val c    = runner.run(rx)(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  private def runCombine(op: String, script: String): String =
    val vars = newVars(2)
    val rx   =
      op match
        case "zip" =>
          vars(0).zip(vars(1))
        case _ =>
          vars(0).join(vars(1))
    val sink = Sink()
    val c    = runner.run(rx)(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  private def runTake(op: String, script: String): String =
    val n    = op.stripPrefix("take:")
    val vars = newVars(1)
    val sink = Sink()
    val c    = runner.run(vars(0).take(n.toInt))(sink(_))
    runScript(script, vars, () => c.cancel)
    sink.result

  private def items(s: String): Seq[(Int, Int)] =
    val body = s.stripPrefix("[").stripSuffix("]")
    if body.isEmpty then
      Seq.empty
    else
      body
        .split(' ')
        .toSeq
        .map { kv =>
          val Array(k, v) = kv.split('=')
          (k.toInt, v.toInt)
        }

  private def runSplit(script: String): String =
    val src  = Rx.variable(Seq((1, 10), (2, 20)))
    val rows = mutable.Map.empty[Int, Rx[(Int, Int)]]
    // Subscribe to each new row when a list holding it is emitted, and record its events
    class SplitSink extends Sink:
      override protected def onValue(v: Any): Unit =
        val pairs = v.asInstanceOf[Seq[(Int, Rx[(Int, Int)])]]
        // The list is recorded before the new rows' first values
        records += s"[${pairs.map(_._1).mkString(" ")}]"
        pairs.foreach { (k, row) =>
          if !rows.get(k).exists(_ eq row) then
            rows(k) = row
            RxRunner.run(row) {
              case OnNext((_, v)) =>
                records += s"${k}=${v}"
              case OnCompletion =>
                records += s"${k}:C"
              case OnFailure(_) =>
                records += s"${k}:E"
              case other =>
                throw IllegalStateException(s"Unexpected row event: ${other}")
            }
        }

    val sink = SplitSink()
    val c    = runner.run(src.split(_._1))(sink(_))
    actions(script).foreach {
      case "stop" =>
        src.stop()
      case "fail" =>
        src.setException(failure)
      case "cancel" =>
        c.cancel
      case a if a.startsWith("set ") =>
        src := items(a.stripPrefix("set "))
      case a =>
        throw IllegalArgumentException(s"Unknown action: ${a}")
    }
    sink.result

  end runSplit

  private def run(c: SpecCase): String =
    c.op match
      case "flatMap" | "transformRx" =>
        runSwitch(c.op, c.script)
      case "recoverWith" =>
        runRecoverWith(c.op, c.script)
      case "merge" =>
        runMerge(c.op, c.script)
      case op if op.startsWith("parFlatMap:") =>
        runParFlatMap(c.op, c.script)
      case "zip" | "join" =>
        runCombine(c.op, c.script)
      case "split" =>
        runSplit(c.script)
      case op if op.startsWith("take:") =>
        runTake(op, c.script)
      case other =>
        throw IllegalArgumentException(s"Unknown operator: ${other}")

  test("load the generated cases") {
    specCases.nonEmpty shouldBe true
  }

  specCases
    .groupBy(_.op)
    .toSeq
    .sortBy(_._1)
    .foreach { (op, cases) =>
      test(op) {
        val actual = cases.map(c => s"${c.script} => ${run(c)}")
        actual shouldBe cases.map(c => s"${c.script} => ${c.expected}")
      }
    }

end RxLiveSpecTest
