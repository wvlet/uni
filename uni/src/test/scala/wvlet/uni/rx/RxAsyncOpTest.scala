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

  // Read a value that is available synchronously; Rx.await is unsupported on Scala.js
  private def get[A](rx: Rx[A]): A =
    collect(rx)._1.head match
      case OnNext(v) =>
        v.asInstanceOf[A]
      case other =>
        fail(s"Unexpected event: ${other}")

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

  test("a cancelled RxSemaphore.acquire leaves the permit available") {
    val semaphore      = RxSemaphore(0)
    val (cancelled, c) = collect(semaphore.acquire)
    get(semaphore.waiting) shouldBe 1
    c.cancel
    get(semaphore.waiting) shouldBe 0
    semaphore.release.run()
    cancelled shouldBe empty
    get(semaphore.available) shouldBe 1
  }

  test("a cancelled RxSemaphore.acquireN lets smaller waiters behind it proceed") {
    val semaphore  = RxSemaphore(0)
    val (large, c) = collect(semaphore.acquireN(2))
    val (small, _) = collect(semaphore.acquire)
    // One permit is not enough for the head waiter, so both keep waiting
    semaphore.release.run()
    small shouldBe empty
    c.cancel
    large shouldBe empty
    small.toSeq shouldBe Seq(OnNext(()), OnCompletion)
    get(semaphore.available) shouldBe 0
  }

  test("a cancelled RxBoundedQueue.take leaves the element for the next taker") {
    val queue          = RxBoundedQueue[Int](1)
    val (cancelled, c) = collect(queue.take)
    c.cancel
    queue.offer(7).run()
    cancelled shouldBe empty
    collect(queue.take)._1.toSeq shouldBe Seq(OnNext(7), OnCompletion)
  }

  test("a cancelled waiting RxBoundedQueue.offer does not enqueue its element") {
    val queue = RxBoundedQueue[Int](1)
    queue.offer(1).run()
    val (cancelled, c) = collect(queue.offer(2))
    c.cancel
    collect(queue.take)._1.toSeq shouldBe Seq(OnNext(1), OnCompletion)
    cancelled shouldBe empty
    get(queue.isEmpty) shouldBe true
  }

  test("AsyncOp reports non-delivery after cancellation") {
    var deliver: scala.util.Try[Int] => Boolean = null
    var unregistered                            = false
    val op                                      = Rx.AsyncOp[Int](
      callback =>
        deliver = callback
        Cancelable(() => unregistered = true)
      ,
      "test"
    )
    val (events, c) = collect(op)
    c.cancel
    unregistered shouldBe true
    deliver(scala.util.Success(1)) shouldBe false
    events shouldBe empty
  }

  test("permits woken for a run cancelled during the same release are released again") {
    val semaphore   = RxSemaphore(0)
    var second      = Cancelable.empty
    val firstEvents = ListBuffer.empty[RxEvent]
    // Both waiters are woken by one release; the first one's handler cancels the second
    RxRunner.run(semaphore.acquire) { ev =>
      firstEvents += ev
      second.cancel
    }
    val secondEvents = ListBuffer.empty[RxEvent]
    second = RxRunner.run(semaphore.acquire)(ev => secondEvents += ev)
    semaphore.releaseN(2).run()
    firstEvents.toSeq shouldBe Seq(OnNext(()), OnCompletion)
    secondEvents shouldBe empty
    get(semaphore.available) shouldBe 1
  }

  test("a cancelled RxDeferred.get unregisters its callback") {
    val deferred    = RxDeferred[Int]()
    val (events, c) = collect(deferred.get)
    val (other, _)  = collect(deferred.get)
    c.cancel
    deferred.complete(3).run()
    events shouldBe empty
    other.toSeq shouldBe Seq(OnNext(3), OnCompletion)
  }

  // Runs scheduled tasks only when the test asks
  private class ManualScheduler extends RxScheduler:
    val tasks                                  = ListBuffer.empty[Runnable]
    override def execute(task: Runnable): Unit = tasks += task
    def runAll(): Unit                         =
      val pending = tasks.toList
      tasks.clear()
      pending.foreach(_.run())

    override def schedule(delay: Long, unit: java.util.concurrent.TimeUnit)(
        task: => Unit
    ): Cancelable = Cancelable.empty

    override def scheduleAtFixedRate(
        initialDelay: Long,
        period: Long,
        unit: java.util.concurrent.TimeUnit
    )(task: => Unit): Cancelable = Cancelable.empty

    override def parallelism: Int = 1
    override def shutdown(): Unit = ()

  test("an RxFiber cancelled before it starts does not run its computation") {
    val scheduler = ManualScheduler()
    val semaphore = RxSemaphore(0)
    var started   = false
    val fiber     = RxFiber.start(
      Rx.single {
          started = true
        }
        .flatMap(_ => semaphore.acquire),
      scheduler
    )
    fiber.cancel.run()
    scheduler.runAll()
    started shouldBe false
    get(semaphore.waiting) shouldBe 0
  }

  test("an RxFiber cancelled while its computation starts unregisters the waiter") {
    val scheduler            = ManualScheduler()
    val semaphore            = RxSemaphore(0)
    var fiber: RxFiber[Unit] = null
    // The cancel arrives after the computation began but before start learns its Cancelable
    val rx = Rx.single(fiber.cancel.run()).flatMap(_ => semaphore.acquire)
    fiber = RxFiber.start(rx, scheduler)
    scheduler.runAll()
    get(semaphore.waiting) shouldBe 0
    semaphore.release.run()
    get(semaphore.available) shouldBe 1
  }

end RxAsyncOpTest
