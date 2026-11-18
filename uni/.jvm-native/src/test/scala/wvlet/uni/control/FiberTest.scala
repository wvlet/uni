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
package wvlet.uni.control

import wvlet.uni.rx.RxScheduler
import wvlet.uni.test.UniTest
import wvlet.uni.util.Result

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FiberTest extends UniTest:

  test("join returns the body's value") {
    val f = Fiber(1 + 2)
    f.join() shouldBe 3
    f.isDone shouldBe true
    f.poll shouldBe Some(Result.Success(3))
  }

  test("join rethrows the body's exception") {
    val f = Fiber[Int](throw IllegalArgumentException("boom"))
    intercept[IllegalArgumentException] {
      f.join()
    }.getMessage shouldBe "boom"
  }

  test("cancel interrupts a blocking call") {
    val started = CountDownLatch(1)
    val f       = Fiber {
      started.countDown()
      Thread.sleep(60000)
      "never"
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    f.cancel()
    intercept[FiberCancelledException] {
      f.join()
    }
    f.isCancelled shouldBe true
  }

  test("a cancelled fiber fails even when the body swallows the interrupt") {
    val started = CountDownLatch(1)
    val f       = Fiber {
      started.countDown()
      try
        Thread.sleep(60000)
      catch
        case _: InterruptedException =>
      "swallowed"
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    f.cancel()
    intercept[FiberCancelledException] {
      f.join()
    }
  }

  test("onCancel hooks run for calls an interrupt cannot stop") {
    val registered = CountDownLatch(1)
    val release    = CountDownLatch(1)
    val f          = Fiber {
      Fiber.onCancel(() => release.countDown())
      registered.countDown()
      // Stands in for an FFI call: wait without reacting to the interrupt
      var done = false
      while !done do
        try
          release.await()
          done = true
        catch
          case _: InterruptedException =>
    }
    registered.await(5, TimeUnit.SECONDS) shouldBe true
    f.cancel()
    intercept[FiberCancelledException] {
      f.join()
    }
  }

  test("checkCancelled stops a CPU loop") {
    val started = CountDownLatch(1)
    val f       = Fiber {
      started.countDown()
      while true do
        Fiber.checkCancelled()
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    f.cancel()
    intercept[FiberCancelledException] {
      f.join()
    }
  }

  test("cancel after completion does nothing") {
    val f = Fiber("done")
    f.join() shouldBe "done"
    f.cancel()
    f.isCancelled shouldBe false
    f.join() shouldBe "done"
  }

  test("an interrupt does not leak to the next task on a pooled thread") {
    // Two reused threads; cancel races completion on each of them
    val scheduler = RxScheduler(2)
    try
      (0 until 500).foreach { _ =>
        val f = Fiber.create(() => 1).start(scheduler)
        f.cancel()
        f.awaitTermination()
      }
      val leaked = AtomicInteger(0)
      val done   = CountDownLatch(64)
      (0 until 64).foreach { _ =>
        scheduler.execute { () =>
          if Thread.currentThread().isInterrupted then
            leaked.incrementAndGet()
          done.countDown()
        }
      }
      done.await(5, TimeUnit.SECONDS) shouldBe true
      leaked.get() shouldBe 0
    finally
      scheduler.shutdown()
  }

  test("par returns both results") {
    Fiber.par(1, "a") shouldBe (1, "a")
  }

  test("parMap keeps input order") {
    Fiber.parMap(1 to 50, maxConcurrency = 8) { i =>
      Thread.sleep((50 - i) % 7)
      i * 2
    } shouldBe (1 to 50).map(_ * 2)
  }

  test("parMap runs at most maxConcurrency bodies at a time") {
    val running = AtomicInteger(0)
    val peak    = AtomicInteger(0)
    Fiber.parMap(1 to 40, maxConcurrency = 4) { i =>
      val n = running.incrementAndGet()
      peak.accumulateAndGet(n, math.max)
      Thread.sleep(5)
      running.decrementAndGet()
      i
    }
    (peak.get() <= 4) shouldBe true
    (peak.get() >= 2) shouldBe true
  }

  test("parMap rejects a non-positive maxConcurrency") {
    intercept[IllegalArgumentException] {
      Fiber.parMap(Seq(1), maxConcurrency = 0)(identity)
    }
  }

  test("parMap rethrows the first failure and cancels the rest") {
    val finished = AtomicInteger(0)
    intercept[IllegalStateException] {
      Fiber.parMap(1 to 20, maxConcurrency = 20) { i =>
        if i == 1 then
          throw IllegalStateException("fail")
        Thread.sleep(60000)
        finished.incrementAndGet()
      }
    }.getMessage shouldBe "fail"
    finished.get() shouldBe 0
  }

  test("race returns the first success and cancels the others") {
    val slowCancelled = AtomicBoolean(false)
    val slowStarted   = CountDownLatch(1)
    val r             = Fiber.race(
      {
        slowStarted.await()
        "fast"
      },
      try
        slowStarted.countDown()
        Thread.sleep(60000)
        "slow"
      catch
        case e: InterruptedException =>
          slowCancelled.set(true)
          throw e
    )
    r shouldBe "fast"
    slowCancelled.get() shouldBe true
  }

  test("race skips failures until one succeeds") {
    Fiber.race[String](
      throw IllegalStateException("a"), {
        Thread.sleep(20)
        "b"
      }
    ) shouldBe "b"
  }

  test("race rethrows the last failure with the others suppressed when all fail") {
    val e = intercept[IllegalStateException] {
      Fiber.race[Int](
        Seq(() => throw IllegalStateException("1"), () => throw IllegalStateException("2"))
      )
    }
    (e.getSuppressed.length) shouldBe 1
  }

  test("scope waits for every fork") {
    val count = AtomicInteger(0)
    Fiber.scope { s =>
      (0 until 10).foreach { _ =>
        s.fork {
          Thread.sleep(10)
          count.incrementAndGet()
        }
      }
    }
    count.get() shouldBe 10
  }

  test("scope cancels siblings after a failure") {
    val siblingCancelled = AtomicBoolean(false)
    val siblingStarted   = CountDownLatch(1)
    intercept[IllegalStateException] {
      Fiber.scope { s =>
        s.fork {
          try
            siblingStarted.countDown()
            Thread.sleep(60000)
          catch
            case _: InterruptedException =>
              siblingCancelled.set(true)
        }
        // Fail only once the sibling runs; a sibling cancelled before it starts never runs its body
        siblingStarted.await()
        s.fork(throw IllegalStateException("fail"))
      }
    }
    siblingCancelled.get() shouldBe true
  }

  test("scope cancels its forks when the block throws") {
    val forkCancelled = AtomicBoolean(false)
    val forkStarted   = CountDownLatch(1)
    intercept[IllegalArgumentException] {
      Fiber.scope { s =>
        s.fork {
          try
            forkStarted.countDown()
            Thread.sleep(60000)
          catch
            case _: InterruptedException =>
              forkCancelled.set(true)
        }
        forkStarted.await()
        throw IllegalArgumentException("block")
      }
    }
    forkCancelled.get() shouldBe true
  }

  test("cancelling a fiber cancels the forks of its inner scope") {
    val started        = CountDownLatch(1)
    val innerCancelled = AtomicBoolean(false)
    val outer          = Fiber {
      Fiber.scope { s =>
        s.fork {
            try
              started.countDown()
              Thread.sleep(60000)
            catch
              case _: InterruptedException =>
                innerCancelled.set(true)
          }
          .join()
      }
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    outer.cancel()
    intercept[FiberCancelledException] {
      outer.join()
    }
    innerCancelled.get() shouldBe true
  }

  // A fork that, once cancelled, starts another fork that would sleep for a minute
  private def forkAfterCancel(s: FiberScope, lateFinished: AtomicBoolean): Unit = s.fork {
    while !Fiber.isCancelled do
      try
        Thread.sleep(1)
      catch
        case _: InterruptedException =>
    s.fork {
      Thread.sleep(60000)
      lateFinished.set(true)
    }
  }

  test("a fork started after the block throws is cancelled") {
    val lateFinished = AtomicBoolean(false)
    val start        = System.nanoTime()
    intercept[IllegalArgumentException] {
      Fiber.scope { s =>
        forkAfterCancel(s, lateFinished)
        throw IllegalArgumentException("block")
      }
    }
    ((System.nanoTime() - start) / 1000000 < 10000) shouldBe true
    lateFinished.get() shouldBe false
  }

  test("a fork started while the scope waits for an interrupted fiber is cancelled") {
    val lateFinished = AtomicBoolean(false)
    val forked       = CountDownLatch(1)
    val outer        = Fiber {
      Fiber.scope { s =>
        forkAfterCancel(s, lateFinished)
        forked.countDown()
      }
    }
    forked.await(5, TimeUnit.SECONDS) shouldBe true
    val start = System.nanoTime()
    outer.cancel()
    intercept[FiberCancelledException] {
      outer.join()
    }
    ((System.nanoTime() - start) / 1000000 < 10000) shouldBe true
    lateFinished.get() shouldBe false
  }

  test("race rethrows a shared failure without suppressing it into itself") {
    val err = IllegalStateException("shared")
    val e   = intercept[IllegalStateException] {
      Fiber.race[Int](throw err, throw err)
    }
    (e eq err) shouldBe true
  }

  test("fork on a closed scope throws") {
    var leaked: FiberScope = null
    Fiber.scope { s =>
      leaked = s
    }
    intercept[IllegalStateException] {
      leaked.fork(1)
    }
  }

end FiberTest
