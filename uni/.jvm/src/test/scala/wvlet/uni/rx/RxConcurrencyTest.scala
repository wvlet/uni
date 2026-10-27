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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
  * Tests for uni-rx concurrency primitives.
  *
  * Waiting operations that complete on the same thread are covered by RxAsyncOpTest.
  */
class RxConcurrencyTest extends UniTest:

  // Helper to run Rx and get the result (synchronous only)
  private def runAndGet[A](rx: Rx[A]): A =
    val result           = new AtomicReference[A]()
    var error: Throwable = null
    RxRunner.runOnce(rx) {
      case OnNext(v) =>
        result.set(v.asInstanceOf[A])
      case OnError(e) =>
        error = e
      case OnCompletion =>
      // Ignore
    }
    if error != null then
      throw error
    result.get()

  // ==================== RxRef Tests ====================

  test("RxRef: get and set") {
    val ref = RxRef(42)
    runAndGet(ref.get) shouldBe 42

    runAndGet(ref.set(100))
    runAndGet(ref.get) shouldBe 100
  }

  test("RxRef: getAndSet") {
    val ref = RxRef("hello")
    val old = runAndGet(ref.getAndSet("world"))
    old shouldBe "hello"
    runAndGet(ref.get) shouldBe "world"
  }

  test("RxRef: update") {
    val ref = RxRef(10)
    runAndGet(ref.update(_ + 5))
    runAndGet(ref.get) shouldBe 15
  }

  test("RxRef: getAndUpdate") {
    val ref = RxRef(10)
    val old = runAndGet(ref.getAndUpdate(_ * 2))
    old shouldBe 10
    runAndGet(ref.get) shouldBe 20
  }

  test("RxRef: updateAndGet") {
    val ref    = RxRef(10)
    val newVal = runAndGet(ref.updateAndGet(_ * 2))
    newVal shouldBe 20
    runAndGet(ref.get) shouldBe 20
  }

  test("RxRef: modify returns result") {
    val ref    = RxRef(List(1, 2, 3))
    val result = runAndGet(
      ref.modify { list =>
        val head = list.head
        (list.tail, head)
      }
    )

    result shouldBe 1
    runAndGet(ref.get) shouldBe List(2, 3)
  }

  test("RxRef: compareAndSet") {
    val ref = RxRef(42)

    runAndGet(ref.compareAndSet(42, 100)) shouldBe true
    runAndGet(ref.get) shouldBe 100

    runAndGet(ref.compareAndSet(42, 200)) shouldBe false
    runAndGet(ref.get) shouldBe 100
  }

  test("RxRef: unsafeGet") {
    val ref = RxRef(42)
    ref.unsafeGet shouldBe 42
  }

  // ==================== RxDeferred Tests (sync operations only) ====================

  test("RxDeferred: complete") {
    val deferred = RxDeferred[Int]()
    runAndGet(deferred.complete(42)) shouldBe true
  }

  test("RxDeferred: tryGet before and after completion") {
    val deferred = RxDeferred[String]()

    runAndGet(deferred.tryGet) shouldBe None
    runAndGet(deferred.isCompleted) shouldBe false

    runAndGet(deferred.complete("done")) shouldBe true
    runAndGet(deferred.tryGet) shouldBe Some("done")
    runAndGet(deferred.isCompleted) shouldBe true
  }

  test("RxDeferred: can only complete once") {
    val deferred = RxDeferred[Int]()

    runAndGet(deferred.complete(1)) shouldBe true
    runAndGet(deferred.complete(2)) shouldBe false
  }

  // ==================== RxSemaphore Tests ====================

  test("RxSemaphore: available permits") {
    val sem = RxSemaphore(2)
    runAndGet(sem.available) shouldBe 2
  }

  test("RxSemaphore: tryAcquire") {
    val sem = RxSemaphore(1)

    runAndGet(sem.tryAcquire) shouldBe true
    runAndGet(sem.available) shouldBe 0

    runAndGet(sem.tryAcquire) shouldBe false
    runAndGet(sem.available) shouldBe 0

    runAndGet(sem.release)
    runAndGet(sem.tryAcquire) shouldBe true
  }

  // ==================== RxScheduler Tests ====================

  test("RxScheduler: parallelism") {
    val scheduler = RxScheduler.default
    (scheduler.parallelism >= 1) shouldBe true
  }

  test("RxScheduler: execute task") {
    val scheduler = RxScheduler.default
    val result    = new AtomicInteger(0)
    val latch     = new CountDownLatch(1)

    scheduler.execute(() =>
      result.set(42)
      latch.countDown()
    )

    latch.await(1, TimeUnit.SECONDS) shouldBe true
    result.get() shouldBe 42
  }

  test("RxScheduler: blocking scheduler") {
    val scheduler = RxScheduler.blocking
    (scheduler.parallelism > 0) shouldBe true
  }

  // ==================== RxBoundedQueue Tests ====================

  test("RxBoundedQueue: tryOffer and tryTake") {
    val queue = RxBoundedQueue[Int](2)

    runAndGet(queue.tryOffer(1)) shouldBe true
    runAndGet(queue.tryOffer(2)) shouldBe true
    runAndGet(queue.tryOffer(3)) shouldBe false // Queue full

    runAndGet(queue.tryTake) shouldBe Some(1)
    runAndGet(queue.tryTake) shouldBe Some(2)
    runAndGet(queue.tryTake) shouldBe None // Queue empty
  }

  test("RxBoundedQueue: size and isEmpty") {
    val queue = RxBoundedQueue[Int](10)

    runAndGet(queue.isEmpty) shouldBe true
    runAndGet(queue.size) shouldBe 0

    runAndGet(queue.tryOffer(1))
    runAndGet(queue.tryOffer(2))

    runAndGet(queue.isEmpty) shouldBe false
    runAndGet(queue.size) shouldBe 2
  }

  test("RxBoundedQueue: isFull") {
    val queue = RxBoundedQueue[Int](2)

    runAndGet(queue.isFull) shouldBe false
    runAndGet(queue.tryOffer(1))
    runAndGet(queue.isFull) shouldBe false
    runAndGet(queue.tryOffer(2))
    runAndGet(queue.isFull) shouldBe true
  }

  test("RxBoundedQueue: capacity") {
    val queue = RxBoundedQueue[Int](5)
    queue.capacity shouldBe 5
  }

  test("RxDeferred: await a value completed from another thread") {
    val deferred            = RxDeferred[Int]()
    val completer: Runnable =
      () =>
        Thread.sleep(50)
        deferred.complete(42).run()
    val thread = Thread(completer)
    thread.start()
    deferred.get.await shouldBe 42
    thread.join()
  }

  test("RxFiber: join returns the fiber result") {
    val fiber = runAndGet(Rx.single(21).map(_ * 2).start)
    fiber.join.await shouldBe 42
  }

end RxConcurrencyTest
