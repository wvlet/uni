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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

class BlockingSchedulerTest extends UniTest:

  // The threads that ran each task, captured on the task's own thread
  private def runTasks(scheduler: RxScheduler, n: Int, sleepMillis: Long = 0): Seq[Thread] =
    val threads = ConcurrentLinkedQueue[Thread]()
    val done    = CountDownLatch(n)
    (0 until n).foreach { _ =>
      scheduler.execute { () =>
        threads.add(Thread.currentThread())
        if sleepMillis > 0 then
          Thread.sleep(sleepMillis)
        done.countDown()
      }
    }
    done.await(10, TimeUnit.SECONDS) shouldBe true
    threads.asScala.toSeq

  test("use virtual threads only from JDK 24") {
    schedulerCompat.useVirtualThreads(17) shouldBe false
    schedulerCompat.useVirtualThreads(21) shouldBe false
    schedulerCompat.useVirtualThreads(23) shouldBe false
    schedulerCompat.useVirtualThreads(24) shouldBe true
    schedulerCompat.useVirtualThreads(25) shouldBe true
  }

  test("RxScheduler.blocking picks the thread kind for the running JDK") {
    val expectVirtual = schedulerCompat.useVirtualThreads(Runtime.version().feature())
    val threads       = runTasks(RxScheduler.blocking, 4)
    threads.forall(_.isVirtual == expectVirtual) shouldBe true
  }

  test("platform-thread scheduler runs tasks on daemon platform threads") {
    val scheduler = schedulerCompat.platformThreadBlockingScheduler()
    try
      val threads = runTasks(scheduler, 4)
      threads.forall(t => !t.isVirtual && t.isDaemon) shouldBe true
      threads.forall(_.getName.startsWith("rx-blocking")) shouldBe true
    finally
      scheduler.shutdown()
  }

  test("virtual-thread scheduler runs each task on a named virtual thread") {
    val scheduler = schedulerCompat.virtualThreadBlockingScheduler()
    try
      val threads = runTasks(scheduler, 4)
      threads.forall(_.isVirtual) shouldBe true
      threads.forall(_.getName.startsWith("rx-blocking-")) shouldBe true
    finally
      scheduler.shutdown()
  }

  test("virtual-thread scheduler runs many sleeping tasks concurrently") {
    val scheduler = schedulerCompat.virtualThreadBlockingScheduler()
    try
      val start = System.nanoTime()
      // 1000 tasks sleeping 200ms each would take minutes if they ran one at a time
      runTasks(scheduler, 1000, sleepMillis = 200)
      val elapsedMillis = (System.nanoTime() - start) / 1000000
      (elapsedMillis < 5000) shouldBe true
    finally
      scheduler.shutdown()
  }

  test("virtual-thread scheduler runs delayed tasks on virtual threads") {
    val scheduler = schedulerCompat.virtualThreadBlockingScheduler()
    try
      val ran       = CountDownLatch(1)
      var isVirtual = false
      scheduler.schedule(10, TimeUnit.MILLISECONDS) {
        isVirtual = Thread.currentThread().isVirtual
        ran.countDown()
      }
      ran.await(5, TimeUnit.SECONDS) shouldBe true
      isVirtual shouldBe true
    finally
      scheduler.shutdown()
  }

  test("evalOnBlocking returns its result through the blocking scheduler") {
    val rx = Rx.single(Thread.currentThread().getName).evalOnBlocking
    rx.await.startsWith("rx-blocking") shouldBe true
  }

end BlockingSchedulerTest
