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

import wvlet.uni.test.AssertionFailure
import wvlet.uni.test.UniTest

class RxTrackerTest extends UniTest:

  test("a subscription stays active until cancelled") {
    val tracker = RxTracker()
    val v       = Rx.variable(1)
    val c       = tracker.trace {
      v.map(_ + 1).subscribe()
    }
    val subs = tracker.activeSubscriptions
    subs.size shouldBe 1
    subs.head.mode shouldBe "runContinuously"
    subs.head.rx shouldMatch { case _: Rx.MapOp[?, ?] =>
    }
    if !isScalaJS && !isScalaNative then
      subs.head.origin shouldMatch {
        case Some(e) if e.getClassName.contains("RxTrackerTest") =>
      }
    c.cancel
    tracker.activeCount shouldBe 0
  }

  test("a completed run ends its subscription") {
    val tracker = RxTracker()
    tracker.trace {
      Rx.sequence(1, 2, 3).map(_ * 2).run()
      Rx.single(1).run()
    }
    tracker.activeCount shouldBe 0
  }

  test("a failed run ends its subscription") {
    val tracker = RxTracker()
    tracker.trace {
      RxRunner.run(Rx.exception[Int](IllegalStateException("boom")))(_ => ())
    }
    tracker.activeCount shouldBe 0
  }

  test("runOnce over a variable stays active until cancelled") {
    val tracker = RxTracker()
    val v       = Rx.variable(1)
    val c       = tracker.trace {
      RxRunner.runOnce(v)(_ => ())
    }
    tracker.activeSubscriptions.map(_.mode) shouldBe Seq("runOnce")
    c.cancel
    tracker.activeCount shouldBe 0
  }

  test("subscriptions outside trace are not tracked") {
    val tracker = RxTracker()
    val c       = Rx.variable(1).subscribe()
    tracker.activeCount shouldBe 0
    c.cancel
  }

  test("nested trace records in every enclosing tracker") {
    val outer = RxTracker(captureStackTrace = false)
    val inner = RxTracker()
    val v     = Rx.variable(1)
    val c     = outer.trace {
      inner.trace {
        v.subscribe()
      }
    }
    outer.activeCount shouldBe 1
    inner.activeCount shouldBe 1
    outer.activeSubscriptions.head.stackTrace shouldBe Seq.empty
    c.cancel
    outer.activeCount shouldBe 0
    inner.activeCount shouldBe 0
  }

  test("assertNoRxLeaks reports a subscription that is not cancelled") {
    val v = Rx.variable(1)
    var c = Cancelable.empty
    val e = intercept[AssertionFailure] {
      assertNoRxLeaks {
        c = v.subscribe()
      }
    }
    c.cancel
    e.getMessage.contains("1 Rx subscription(s) still active") shouldBe true
  }

  test("assertNoRxLeaks passes when subscriptions are cancelled") {
    val v = Rx.variable(1)
    val n = assertNoRxLeaks {
      val c = v.subscribe()
      c.cancel
      42
    }
    n shouldBe 42
  }

  test("the global tracker records subscriptions from any code") {
    val tracker = RxTracker.startGlobal(captureStackTrace = false)
    try
      RxTracker.global shouldBe Some(tracker)
      val c = Rx.variable(1).subscribe()
      tracker.activeSubscriptions.nonEmpty shouldBe true
      c.cancel
    finally
      RxTracker.stopGlobal()
    RxTracker.global shouldBe None
  }

end RxTrackerTest
