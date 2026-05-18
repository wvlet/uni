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
package wvlet.uni.concurrent

import java.util.concurrent.atomic.AtomicInteger
import wvlet.uni.test.UniTest

/**
  * Cross-platform feasibility test for [[Task]]. Validates that the same body — a counter that
  * reports progress every iteration and exits on cancel — runs to completion on every supported
  * platform and that the lifecycle observers (`awaitRx`, `progress`, `state`) agree.
  *
  * All tests use [[Task.awaitRx]] for completion so the same code runs on JVM, Scala.js, and Scala
  * Native. Blocking [[Task.await]] is exercised in platform-specific tests.
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

  test("reports progress and exposes the latest snapshot") {
    val task = Task.run { ctx =>
      ctx.reportProgress("step-1")
      ctx.reportProgress("step-2")
    }
    task
      .awaitRx
      .map { _ =>
        task.progress shouldBe Some("step-2")
      }
  }

  test("emits each progress snapshot on progressStream") {
    val task = Task.run { ctx =>
      ctx.reportProgress(1)
      ctx.reportProgress(2)
      ctx.reportProgress(3)
    }
    // Subscribe to the stream before the body actually runs.
    val collected = scala.collection.mutable.ArrayBuffer.empty[Task.Progress]
    task
      .progressStream
      .tap(p => collected += p)
      .run { _ =>
      }
    task
      .awaitRx
      .map { _ =>
        // The stream is hot — we may miss early snapshots depending on scheduling order — but
        // the body emits 1,2,3 sequentially, so the *last* value is deterministic.
        collected.lastOption shouldBe Some(3)
      }
  }

  // Note: cancel timing — "cancel before the body starts" is only deterministic on Scala.js (the
  // body is queued as a microtask). On JVM/Native, `Task.run` may start the OS thread synchronously
  // so the body races the caller's next instruction. Cancel behaviour is exercised in
  // platform-specific tests (JsTaskTest, JvmTaskTest, NativeTaskTest).

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

end TaskFeasibilityTest
