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
    * Run on an RxRunner instance, which shows the internal events.
    */
  private def internalEvents[A](rx: Rx[A])(body: => Unit): Seq[RxEvent] =
    val events = Seq.newBuilder[RxEvent]
    val c      =
      RxRunner().run(rx) { ev =>
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

  test("a failed source ends with OnFailure") {
    val v      = Rx.variable(1)
    val events =
      internalEvents(v.map(_ + 1)) {
        v.setException(boom)
      }
    events shouldBe Seq(OnNext(2), OnFailure(boom))
  }

  test("run and runOnce end with OnFailure; runContinuously delivers a plain OnError") {
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
    runEvents.result() shouldBe Seq(OnNext(1), OnFailure(boom))

    var first: RxEvent = null
    RxRunner.runOnce(Rx.single(1).map(_ => throw boom))(first = _)
    first shouldBe OnFailure(boom)

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

  test("run delivers nothing after a failed end, even from a live RxVar") {
    val v      = Rx.variable(1)
    val events = Seq.newBuilder[RxEvent]
    val c      =
      RxRunner.run(
        v.map(x =>
          if x == 2 then
            throw boom
          else
            x
        )
      )(events += _)
    v := 2
    v := 4
    c.cancel
    events.result() shouldBe Seq(OnNext(1), OnFailure(boom))

    val w       = Rx.variable(1)
    val wEvents = Seq.newBuilder[RxEvent]
    val c2      = RxRunner.run(w)(wEvents += _)
    w.setException(boom)
    w := 4
    c2.cancel
    wEvents.result() shouldBe Seq(OnNext(1), OnFailure(boom))
  }

  test("run completes a zip of finite sources") {
    val events = Seq.newBuilder[RxEvent]
    RxRunner.run(Rx.sequence(1, 2).zip(Rx.sequence("a", "b", "c")))(events += _)
    events.result() shouldBe Seq(OnNext((1, "a")), OnNext((2, "b")), OnCompletion)
  }

  test("a failed source ends merge and cancels the other source") {
    val a      = Rx.variable(1)
    val b      = Rx.variable(10)
    val events = Seq.newBuilder[RxEvent]
    val c      = RxRunner.runContinuously(a.merge(b))(events += _)
    a.setException(boom)
    b := 20
    c.cancel
    events.result() shouldBe Seq(OnNext(1), OnNext(10), OnFailure(boom))
  }

  test("runContinuously answers Continue to an error, so a finite source keeps going") {
    val events = Seq.newBuilder[RxEvent]
    RxRunner.runContinuously(
      Rx.sequence(1, 2, 3)
        .map(x =>
          if x == 2 then
            throw boom
          else
            x
        )
    )(events += _)
    events.result() shouldMatch {
      case Seq(OnNext(1), OnError(e), OnNext(3), OnCompletion) if e eq boom =>
    }
  }

  test("a downstream Stop ends take before the nth value") {
    val v      = Rx.variable(1)
    val events = Seq.newBuilder[RxEvent]
    RxRunner().run(v.take(3)) { ev =>
      events += ev
      RxResult.Stop
    }
    v := 2
    v.stop()
    events.result() shouldBe Seq(OnNext(1))
  }

  test("a downstream Stop ends Rx.sequence and merge") {
    val seqEvents = Seq.newBuilder[RxEvent]
    RxRunner().run(Rx.sequence(1, 2, 3)) { ev =>
      seqEvents += ev
      RxResult.Stop
    }
    seqEvents.result() shouldBe Seq(OnNext(1))

    val a           = Rx.variable(1)
    val b           = Rx.variable(10)
    val mergeEvents = Seq.newBuilder[RxEvent]
    RxRunner().run(a.merge(b)) { ev =>
      mergeEvents += ev
      RxResult.Stop
    }
    a := 2
    b := 20
    mergeEvents.result() shouldBe Seq(OnNext(1))
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
