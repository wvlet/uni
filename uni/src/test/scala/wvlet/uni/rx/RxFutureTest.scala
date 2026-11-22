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

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * A future that completes before anyone subscribes, e.g. a connect refused on another thread
  * before the caller runs the returned Rx, still reaches the subscriber.
  */
class RxFutureTest extends UniTest:

  private given ExecutionContext = ExecutionContext.parasitic

  private val ex = IllegalStateException("refused")

  private def events(rx: RxOps[?]): List[RxEvent] =
    val b = ListBuffer.empty[RxEvent]
    RxRunner.run(rx)(b += _)
    b.toList

  test("deliver a failure that happened before the subscription") {
    events(Rx.future(Future.failed[Int](ex))) shouldBe List(OnFailure(ex))
  }

  test("deliver a value and the completion that happened before the subscription") {
    events(Rx.future(Future.successful(1))) shouldBe List(OnNext(1), OnCompletion)
  }

  test("deliver each event once to a subscriber that attached before completion") {
    val p  = Promise[Int]()
    val b  = ListBuffer.empty[RxEvent]
    val rx = Rx.future(p.future)
    RxRunner.run(rx)(b += _)
    p.success(2)
    b.toList shouldBe List(OnNext(2), OnCompletion)

    val failed = Promise[Int]()
    val b2     = ListBuffer.empty[RxEvent]
    RxRunner.run(Rx.future(failed.future))(b2 += _)
    failed.failure(ex)
    b2.toList shouldBe List(OnFailure(ex))
  }

  test("runOnce reports a failure that happened before it ran") {
    var got: RxEvent = null
    RxRunner.runOnce(Rx.future(Future.failed[Int](ex)))(got = _)
    got shouldBe OnFailure(ex)
  }

  test("fromFuture reports the waiting state, then a failure that happened before it ran") {
    events(Rx.fromFuture(Future.failed[Int](ex))) shouldBe List(OnNext(None), OnFailure(ex))
  }

end RxFutureTest
