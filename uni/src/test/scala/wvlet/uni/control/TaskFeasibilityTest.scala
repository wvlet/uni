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

import java.util.concurrent.atomic.AtomicInteger
import wvlet.uni.rx.RxVar
import wvlet.uni.test.UniTest

/**
  * Cross-platform feasibility test for [[Task]]. The same body — exercise the lifecycle and publish
  * a side-channel observation through an `RxVar` (the "progress channel is just an Rx the body
  * writes to" pattern from the design doc) — must work on JVM, Scala.js, and Scala Native.
  *
  * All tests use `awaitRx` so the same code runs everywhere. Blocking `await()` is exercised in
  * platform-specific tests.
  */
class TaskFeasibilityTest extends UniTest:

  test("runs body to completion and observes terminal state") {
    val counter = new AtomicInteger(0)
    val task    = Task.run { _ =>
      counter.set(5)
    }
    task
      .awaitRx
      .map { _ =>
        counter.get() shouldBe 5
        task.state shouldBe Task.State.Succeeded
        task.isDone shouldBe true
      }
  }

  test("body publishes progress via a caller-owned RxVar") {
    // No progress API on Task itself — bodies write to whatever channel they like.
    val progress = RxVar[Option[String]](None)
    val task     = Task.run { _ =>
      progress.update(_ => Some("step-1"), force = true)
      progress.update(_ => Some("step-2"), force = true)
    }
    task
      .awaitRx
      .map { _ =>
        progress.get shouldBe Some("step-2")
      }
  }

  test("body failure is reported via awaitRx and state") {
    val bug  = new RuntimeException("boom")
    val task = Task.run { _ =>
      throw bug
    }
    task
      .awaitRx
      .recover { case e: RuntimeException =>
        e shouldBeTheSameInstanceAs bug
      }
      .map { _ =>
        task.state shouldBe Task.State.Failed
      }
  }

  test("double cancel is idempotent") {
    val task = Task.run { _ =>
      ()
    }
    task.cancel()
    task.cancel() // must not throw or alter the terminal state once observed
    task
      .awaitRx
      .transform { _ =>
        // Don't care if Succeeded (race won by the body) or Cancelled (race won by cancel) —
        // just need the second cancel to be a no-op against any terminal state.
        task.isDone shouldBe true
      }
  }

  test("cancel after successful completion leaves state as Succeeded") {
    val task = Task.run { _ =>
      ()
    }
    task
      .awaitRx
      .map { _ =>
        task.state shouldBe Task.State.Succeeded
        task.cancel()
        task.state shouldBe Task.State.Succeeded
      }
  }

  test("awaitRx returns the same Rx on repeated calls") {
    val task = Task.run { _ =>
      ()
    }
    // Memoised — multiple subscribers share one chain rather than allocating per call.
    (task.awaitRx eq task.awaitRx) shouldBe true
    task.awaitRx
  }

end TaskFeasibilityTest
