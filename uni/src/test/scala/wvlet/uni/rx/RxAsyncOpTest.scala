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

import wvlet.uni.test.*

import scala.collection.mutable.ListBuffer

/**
  * Runs the waiting operations of RxDeferred, RxSemaphore, and RxBoundedQueue, which complete
  * through a callback. Completion happens on the test thread, so these run on every platform.
  */
class RxAsyncOpTest extends UniTest:

  private def collect[A](rx: Rx[A]): (ListBuffer[RxEvent], Cancelable) =
    val events = ListBuffer.empty[RxEvent]
    val c      = RxRunner.run(rx)(ev => events += ev)
    (events, c)

  test("RxDeferred.get emits a value completed later") {
    val deferred    = RxDeferred[Int]()
    val (events, _) = collect(deferred.get)
    events shouldBe empty
    deferred.complete(42).run()
    events.toSeq shouldBe Seq(OnNext(42), OnCompletion)
  }

  test("RxDeferred.get emits an already completed value") {
    val deferred = RxDeferred[String]()
    deferred.complete("done").run()
    collect(deferred.get)._1.toSeq shouldBe Seq(OnNext("done"), OnCompletion)
  }

  test("RxDeferred.get propagates an error") {
    val deferred    = RxDeferred[Int]()
    val error       = IllegalStateException("failed")
    val (events, _) = collect(deferred.get.map(_ + 1))
    deferred.completeWithError(error).run()
    events.toSeq shouldBe Seq(OnError(error))
  }

  test("RxDeferred.get emits nothing after cancellation") {
    val deferred    = RxDeferred[Int]()
    val (events, c) = collect(deferred.get)
    c.cancel
    deferred.complete(1).run()
    events shouldBe empty
  }

  test("RxSemaphore.acquire waits for a release") {
    val semaphore = RxSemaphore(1)
    collect(semaphore.acquire)._1.toSeq shouldBe Seq(OnNext(()), OnCompletion)

    val (events, _) = collect(semaphore.acquire)
    events shouldBe empty
    semaphore.release.run()
    events.toSeq shouldBe Seq(OnNext(()), OnCompletion)
  }

  test("RxBoundedQueue.take waits for an offer") {
    val queue       = RxBoundedQueue[Int](1)
    val (events, _) = collect(queue.take)
    events shouldBe empty
    queue.offer(7).run()
    events.toSeq shouldBe Seq(OnNext(7), OnCompletion)
  }

  test("RxBoundedQueue.offer waits for space") {
    val queue = RxBoundedQueue[Int](1)
    queue.offer(1).run()
    val (events, _) = collect(queue.offer(2))
    events shouldBe empty
    collect(queue.take)._1.toSeq shouldBe Seq(OnNext(1), OnCompletion)
    events.toSeq shouldBe Seq(OnNext(()), OnCompletion)
    collect(queue.take)._1.toSeq shouldBe Seq(OnNext(2), OnCompletion)
  }

end RxAsyncOpTest
