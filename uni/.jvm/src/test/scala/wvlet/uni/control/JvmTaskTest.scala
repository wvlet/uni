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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import wvlet.uni.test.UniTest

/**
  * JVM-specific Task tests — blocking [[Task.await]] and `Thread.interrupt`-driven cancel of a body
  * that is sleeping when cancel arrives.
  */
class JvmTaskTest extends UniTest:

  test("await() blocks until the body completes") {
    val started = new CountDownLatch(1)
    val counter = new AtomicInteger(0)
    val task    = Task.run { _ =>
      started.countDown()
      // Released by the test thread after it observes the latch, so this is deterministic
      // (no race on Thread.sleep timing).
      counter.set(42)
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    task.await()
    counter.get() shouldBe 42
    task.state shouldBe Task.State.Succeeded
  }

  test("await() rethrows the body's exception on failure") {
    val bug  = new RuntimeException("nope")
    val task = Task.run { _ =>
      throw bug
    }
    val caught =
      try
        task.await()
        null
      catch
        case e: RuntimeException =>
          e
    caught shouldBeTheSameInstanceAs bug
    task.state shouldBe Task.State.Failed
  }

  test("cancel interrupts a body blocked in Thread.sleep") {
    val started     = new CountDownLatch(1)
    val interrupted = new AtomicBoolean(false)
    val task        = Task.run { _ =>
      started.countDown()
      try
        // Without interrupt this would block the whole test for 10s.
        Thread.sleep(10000)
      catch
        case _: InterruptedException =>
          interrupted.set(true)
          throw new InterruptedException("cancelled mid-sleep")
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    task.cancel()
    try
      task.await()
    catch
      case _: InterruptedException =>
        ()
    interrupted.get() shouldBe true
    task.state shouldBe Task.State.Cancelled
  }

end JvmTaskTest
