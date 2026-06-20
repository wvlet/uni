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

import wvlet.uni.util.Result
import wvlet.uni.test.UniTest

/**
  * Cross-platform behavior of [[BackgroundTask]]. These assert outcomes (not concurrency), so they
  * hold on JS too, where the body runs inline during `start`. Concurrency (live cancel/progress) is
  * covered by the JVM/Native-only tests.
  */
class BackgroundTaskTest extends UniTest:

  test("completes with Success") {
    val task = BackgroundTask.start[Int, Unit](_ => 21 * 2)
    task.await() shouldBe Result.Success(42)
    task.poll shouldBe Some(Result.Success(42))
    task.isDone shouldBe true
    task.isCancelled shouldBe false
  }

  test("a body exception becomes Failure") {
    val task = BackgroundTask.start[Int, Unit](_ => throw RuntimeException("boom"))
    task.await() shouldMatch { case Result.Failure(e) =>
      e.getMessage shouldBe "boom"
    }
  }

  test("reports the latest progress") {
    val task = BackgroundTask.start[Int, Int] { ctx =>
      ctx.reportProgress(1)
      ctx.reportProgress(2)
      99
    }
    task.await() shouldBe Result.Success(99)
    task.progress shouldBe Some(2)
  }

  test("await and poll agree once done") {
    val task    = BackgroundTask.start[String, Unit](_ => "ok")
    val awaited = task.await()
    awaited shouldBe Result.Success("ok")
    task.poll shouldBe Some(awaited)
  }

end BackgroundTaskTest
