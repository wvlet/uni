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

class RxResumableErrorTest extends UniTest:
  private val boom = IllegalStateException("boom")

  /**
    * Run in continuous mode on an RxRunner instance, which shows the internal events.
    */
  private def internalEvents[A](rx: Rx[A])(body: => Unit): Seq[RxEvent] =
    val events = Seq.newBuilder[RxEvent]
    val c      =
      RxRunner(continuous = true).run(rx) { ev =>
        events += ev
        RxResult.Continue
      }
    try body
    finally c.cancel
    events.result()

  private def isPlain(ev: RxEvent): Boolean = ResumableError.unapply(ev).isEmpty

  test("a resumable error is an OnError") {
    val ev: RxEvent = ResumableError(boom)
    ev shouldMatch {
      case OnError(e) if e eq boom =>
    }
    (ev == OnError(boom)) shouldBe true
    (OnError(boom) == ev) shouldBe true
    isPlain(OnError(boom)) shouldBe true
  }

  test("operator functions raise a resumable error, and the source keeps running") {
    val fail                         = (x: Int) => x == 2
    val ops: Seq[Rx[Int] => Rx[Any]] = Seq(
      _.map(x =>
        if fail(x) then
          throw boom
        else
          x
      ),
      _.filter(x =>
        if fail(x) then
          throw boom
        else
          true
      ),
      _.scan(0)((acc, x) =>
        if fail(x) then
          throw boom
        else
          acc + x
      ),
      _.flatMap(x =>
        if fail(x) then
          throw boom
        else
          Rx.single(x)
      ),
      _.tap(x =>
        if fail(x) then
          throw boom
      ),
      _.transform(t =>
        if t.toOption.exists(fail) then
          throw boom
        else
          t
      ),
      _.transformRx(t =>
        if t.toOption.exists(fail) then
          throw boom
        else
          Rx.single(t.get)
      )
    )
    ops.foreach { op =>
      val v      = Rx.variable(1)
      val events =
        internalEvents(op(v)) {
          v := 2
          v := 3
        }
      events.collect { case e: OnError =>
        e
      } shouldMatch {
        case Seq(ResumableError(e)) if e eq boom =>
      }
      events.lastOption shouldMatch { case Some(OnNext(_)) =>
      }
    }
  }

  test("a source error stays a plain OnError") {
    val v      = Rx.variable(1)
    val events =
      internalEvents(v.map(_ + 1)) {
        v.setException(boom)
      }
    events shouldMatch {
      case Seq(OnNext(2), e: OnError) if isPlain(e) =>
    }
  }

  test("run and runOnce end with a failed completion; runContinuously delivers a plain OnError") {
    val failing = Rx
      .sequence(1, 2)
      .map(x =>
        if x == 2 then
          throw boom
        else
          x
      )

    val runEvents = Seq.newBuilder[RxEvent]
    RxRunner.run(failing)(runEvents += _)
    runEvents.result() shouldBe Seq(OnNext(1), OnCompletion.failure(boom))

    var first: RxEvent = null
    RxRunner.runOnce(Rx.single(1).map(_ => throw boom))(first = _)
    first shouldBe OnCompletion.failure(boom)

    val v          = Rx.variable(1)
    val contEvents = Seq.newBuilder[RxEvent]
    val c          =
      RxRunner.runContinuously(
        v.map(x =>
          if x == 2 then
            throw boom
          else
            x
        )
      )(contEvents += _)
    v := 2
    v := 3
    c.cancel
    contEvents.result() shouldMatch {
      case Seq(OnNext(1), e: OnError, OnNext(3)) if isPlain(e) =>
    }
  }

  test("recover handles a resumable error as an error") {
    val v      = Rx.variable(1)
    val events =
      internalEvents(
        v.map(x =>
            if x == 2 then
              throw boom
            else
              x
          )
          .recover { case _ =>
            0
          }
      ) {
        v := 2
        v := 3
      }
    events shouldBe Seq(OnNext(1), OnNext(0), OnNext(3))
  }

end RxResumableErrorTest
