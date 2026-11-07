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

class RxOnCancelTest extends UniTest:
  test("run onCancel when a running subscription is cancelled") {
    var cancelled = 0
    val v         = Rx.variable(1)
    val values    = Seq.newBuilder[Int]
    val c         = v.onCancel(cancelled += 1).run(values += _)
    v := 2
    cancelled shouldBe 0
    c.cancel
    cancelled shouldBe 1
    // Cancelling again doesn't run it twice
    c.cancel
    cancelled shouldBe 1
    v := 3
    values.result() shouldBe Seq(1, 2)
  }

  test("not run onCancel after the stream completed") {
    var cancelled = 0
    val c         = Rx.fromSeq(Seq(1, 2, 3)).onCancel(cancelled += 1).run()
    c.cancel
    cancelled shouldBe 0
  }

  test("not run onCancel after the stream failed") {
    var cancelled = 0
    val c         = Rx
      .exception[Int](IllegalStateException("boom"))
      .onCancel(cancelled += 1)
      .recover { case _: IllegalStateException =>
        0
      }
      .run()
    c.cancel
    cancelled shouldBe 0
  }

  test("not run onCancel after an RxVar stopped") {
    var cancelled = 0
    val v         = Rx.variable(1)
    val c         = v.onCancel(cancelled += 1).run()
    v.stop()
    c.cancel
    cancelled shouldBe 0
  }

  test("run onCancel when take(n) cancels its upstream") {
    var cancelled = 0
    val v         = Rx.variable(1)
    val values    = Seq.newBuilder[Int]
    v.onCancel(cancelled += 1).take(2).run(values += _)
    v := 2
    values.result() shouldBe Seq(1, 2)
    cancelled shouldBe 1
  }

  test("run onCancel through downstream operators") {
    var cancelled = 0
    val v         = Rx.variable(1)
    val c         = v
      .onCancel(cancelled += 1)
      .map(_ * 2)
      .flatMap(x => Rx.single(x))
      .concat(Rx.single(0))
      .subscribe()
    c.cancel
    cancelled shouldBe 1
  }

  test("run onCancel of each subscription") {
    var cancelled = 0
    val rx        = Rx.variable(1).onCancel(cancelled += 1)
    val c1        = rx.run()
    val c2        = rx.run()
    c1.cancel
    cancelled shouldBe 1
    c2.cancel
    cancelled shouldBe 2
  }

  test("swallow an exception thrown by onCancel") {
    val c = Rx.variable(1).onCancel(throw IllegalStateException("expected cleanup failure")).run()
    // Logged as a warning, not rethrown
    c.cancel
  }

end RxOnCancelTest
