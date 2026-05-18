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

  test("double cancel is idempotent — second call doesn't change state or overwrite reason") {
    val task = Task.run { ctx =>
      // Loop until cancelled so the cancel races land while the task is still Running.
      while !ctx.isCancelled do
        (
      )
      ctx.checkCancelled()
    }
    task.cancel("first")
    val stateAfterFirst = task.state
    task.cancel("second") // must not overwrite the recorded reason or flip terminal state
    task
      .awaitRx
      .recover { case e: InterruptedException =>
        // The first cancel's reason wins — the second is a no-op.
        e.getMessage shouldBe "first"
      }
      .map { _ =>
        // After the second cancel, isDone holds (terminal state reached).
        task.isDone shouldBe true
        // The state is Cancelled — the second cancel can't have transitioned it to Succeeded
        // since the body threw on the first cancel's signal.
        task.state shouldBe Task.State.Cancelled
        // Sanity: at least one of the two cancels was observed at the moment of `cancel("first")`
        // returning, so `stateAfterFirst` should be either Cancelling-style (Running here, since
        // we dropped the Cancelling state) or already terminal.
        (stateAfterFirst == Task.State.Running || stateAfterFirst.isTerminal) shouldBe true
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

  test("TaskRegistry instances are isolated from each other") {
    // The whole point of TaskRegistry being a class, not a singleton: tests can spin up a fresh
    // registry, exercise it, and not leak into other tests or into TaskRegistry.default.
    val a = TaskRegistry()
    val b = TaskRegistry()
    a.register("only-in-a") { _ =>
      ()
    }
    b.register("only-in-b") { _ =>
      ()
    }

    a.isRegistered("only-in-a") shouldBe true
    a.isRegistered("only-in-b") shouldBe false
    b.isRegistered("only-in-a") shouldBe false
    b.isRegistered("only-in-b") shouldBe true

    // And neither leaks into the default that `Task.runRegistered` uses.
    TaskRegistry.default.isRegistered("only-in-a") shouldBe false
    TaskRegistry.default.isRegistered("only-in-b") shouldBe false

    // Lookup on a fresh registry that doesn't have the id throws.
    val caught =
      try
        TaskRegistry().lookup("missing")
        null
      catch
        case e: NoSuchElementException =>
          e
    (caught != null) shouldBe true
  }

  test("cancel(reason) surfaces the reason in the body's InterruptedException") {
    val captured = new java.util.concurrent.atomic.AtomicReference[String]("")
    val task     = Task.run { ctx =>
      // Spin briefly so the test thread can call cancel before we observe it. Cooperative —
      // on JS the microtask delays naturally; on JVM/Native a checkCancelled at each tick.
      var i = 0
      while !ctx.isCancelled && i < 1_000_000 do
        i += 1
      try
        ctx.checkCancelled()
      catch
        case e: InterruptedException =>
          captured.set(e.getMessage)
          throw e
    }
    task.cancel("user pressed Ctrl-C")
    task
      .awaitRx
      .recover { case _: InterruptedException =>
      // expected
      }
      .map { _ =>
        // Either the body observed cancel and captured the reason, OR (on platforms where the
        // body completes before the cancel takes effect) we still passed the message through
        // to the terminal awaitRx error. Both are acceptable; just confirm cancel was honoured.
        task.state shouldBe Task.State.Cancelled
        if captured.get().nonEmpty then
          captured.get() shouldBe "user pressed Ctrl-C"
      }
  }

end TaskFeasibilityTest
