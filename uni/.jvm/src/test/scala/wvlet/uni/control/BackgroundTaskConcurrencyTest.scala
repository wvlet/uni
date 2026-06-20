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

import wvlet.uni.rx.{OnCompletion, OnError, OnNext, RxRunner}
import wvlet.uni.util.{Result, ThreadUtil}
import wvlet.uni.test.UniTest

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable.ArrayBuffer

/**
  * True-concurrency behavior of [[BackgroundTask]] on the JVM (a real background thread). Mirrored
  * for Native; not applicable on JS, where the body runs inline.
  */
class BackgroundTaskConcurrencyTest extends UniTest:

  test("await blocks until the worker completes") {
    val task = BackgroundTask.start[String, Unit] { _ =>
      ThreadUtil.sleep(100)
      "done"
    }
    // If await did not block on the gate, resultRef would still be None and .get would throw.
    task.await() shouldBe Result.Success("done")
  }

  test("cooperative cancel is observed via isCancelled") {
    val progressed = CountDownLatch(1)
    val task       = BackgroundTask.start[Int, Int] { ctx =>
      var i = 0
      while !ctx.isCancelled do
        i += 1
        ctx.reportProgress(i)
        if i == 1 then
          progressed.countDown()
        ThreadUtil.sleep(5)
      i
    }
    progressed.await(5, TimeUnit.SECONDS) shouldBe true
    task.progress.exists(_ >= 1) shouldBe true
    task.cancel()
    task.await().isSuccess shouldBe true // the loop exited and returned i
    task.isCancelled shouldBe true
  }

  test("checkCancelled aborts with TaskCancelledException") {
    val started = CountDownLatch(1)
    val task    = BackgroundTask.start[Int, Unit] { ctx =>
      started.countDown()
      var n = 0
      while n < 1000000 do
        ctx.checkCancelled()
        ThreadUtil.sleep(5)
        n += 1
      n
    }
    started.await(5, TimeUnit.SECONDS) shouldBe true
    task.cancel()
    task.await() shouldMatch { case Result.Failure(_: TaskCancelledException) =>
    }
  }

  test("onCancel hook fires on cancel") {
    val registered = CountDownLatch(1)
    val hookRan    = CountDownLatch(1)
    val task       = BackgroundTask.start[Int, Unit] { ctx =>
      ctx.onCancel(() => hookRan.countDown())
      registered.countDown()
      while !ctx.isCancelled do
        ThreadUtil.sleep(5)
      0
    }
    registered.await(5, TimeUnit.SECONDS) shouldBe true
    task.cancel()
    hookRan.await(5, TimeUnit.SECONDS) shouldBe true
    task.await().isSuccess shouldBe true
  }

  test("progressStream pushes reported progress and completes") {
    val canReport = CountDownLatch(1)
    val task      = BackgroundTask.start[Int, Int] { ctx =>
      canReport.await() // wait until the subscriber is attached, so emissions are deterministic
      ctx.reportProgress(1)
      ctx.reportProgress(2)
      ctx.reportProgress(3)
      0
    }
    val seen      = ArrayBuffer.empty[Int]
    val completed = CountDownLatch(1)
    RxRunner.run(task.progressStream) {
      case OnNext(v) =>
        seen += v.asInstanceOf[Int]
      case OnCompletion =>
        completed.countDown()
      case OnError(_) =>
        completed.countDown()
    }
    canReport.countDown()
    completed.await(5, TimeUnit.SECONDS) shouldBe true // stream completed when the task finished
    seen.toSeq shouldBe Seq(1, 2, 3)
    task.await().isSuccess shouldBe true
  }

  test("onCancel registered after cancellation runs immediately") {
    val ranImmediately = CountDownLatch(1)
    val task           = BackgroundTask.start[Int, Unit] { ctx =>
      while !ctx.isCancelled do
        ThreadUtil.sleep(5)
      // Registered after cancel() has fired → must run right away.
      ctx.onCancel(() => ranImmediately.countDown())
      0
    }
    task.cancel()
    ranImmediately.await(5, TimeUnit.SECONDS) shouldBe true
    task.await().isSuccess shouldBe true
  }

end BackgroundTaskConcurrencyTest
