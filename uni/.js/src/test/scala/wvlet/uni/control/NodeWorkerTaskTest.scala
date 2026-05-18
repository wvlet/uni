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

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import wvlet.uni.test.UniTest

/**
  * Force-init shim: the @JSExportTopLevel `val` is computed eagerly at module load, which
  * initialises this object in *both* the main isolate and the worker isolate (the worker
  * dynamically imports the same bundle, re-running module init). Result: `Task.register` calls here
  * populate the registry on whichever side looks them up — main for the test, worker for the actual
  * body execution.
  */
private object NodeWorkerTaskTestRegistry:

  // Body 1: a no-op that exits cleanly.
  Task.register("nwt-no-op") { _ =>
    ()
  }

  // Body 2: a deliberately-slow busy loop that throws on cancel via `checkCancelled`. Used to
  // verify that (a) `await()` actually blocks the main thread (not returns immediately), and
  // (b) `cancel` crosses the isolate boundary via SAB and is observed inside the worker. The
  // throwing path ensures the worker reports `StateCancelled` rather than `StateSuccess` (which
  // is what a cooperative early-return loop would produce).
  Task.register("nwt-counter") { ctx =>
    var i = 0
    while i < 200_000_000 do
      // Check every ~10ms-worth of iterations so cancel observation is quick.
      if (i & 0xfffff) == 0 then
        ctx.checkCancelled()
      i += 1
  }

  // Body 3: throws — exercises the error-propagation path through the SAB.
  Task.register("nwt-boom") { _ =>
    throw new RuntimeException("nwt-boom message")
  }

  // Body 4: cooperatively self-cancels via `checkCancelled` after we set the flag.
  Task.register("nwt-checked") { ctx =>
    ctx.checkCancelled() // throws if cancel arrived first
  }

  /**
    * Forces eager init of this object at bundle load. The `val`'s body runs at module load time, so
    * the `Task.register` side effects above complete before any test code or worker code runs.
    */
  @JSExportTopLevel("__uniTaskNodeTestRegistered")
  val isRegistered: Boolean = true

end NodeWorkerTaskTestRegistry

/**
  * Node-specific tests for `Task.runRegistered` blocking `await()`. Skipped on the browser because
  * `Atomics.wait` is unavailable on the main thread there.
  */
class NodeWorkerTaskTest extends UniTest:

  // Touch the registry object so its init runs even on platforms where @JSExportTopLevel might
  // not trigger eager init (belt-and-braces; the export already forces it).
  NodeWorkerTaskTestRegistry

  private def isNode: Boolean =
    !js.isUndefined(js.Dynamic.global.process) &&
      !js.isUndefined(js.Dynamic.global.process.versions) &&
      !js.isUndefined(js.Dynamic.global.process.versions.node)

  private def skipIfNotNode(): Unit =
    if !isNode then
      skip("Node-only: runRegistered uses worker_threads + Atomics.wait")

  test("runRegistered + await() succeeds on Node") {
    skipIfNotNode()
    val task = Task.runRegistered("nwt-no-op")
    task.await()
    task.state shouldBe Task.State.Succeeded
  }

  test("runRegistered + await() rethrows the body's exception") {
    skipIfNotNode()
    val task   = Task.runRegistered("nwt-boom")
    val caught =
      try
        task.await()
        null
      catch
        case e: RuntimeException =>
          e
    (caught != null) shouldBe true
    caught.getMessage shouldContain "nwt-boom message"
    task.state shouldBe Task.State.Failed
  }

  test("cancel + await() throws InterruptedException with the cancel reason") {
    skipIfNotNode()
    val task = Task.runRegistered("nwt-counter")
    task.cancel("user pressed Ctrl-C")
    val caught =
      try
        task.await()
        null
      catch
        case e: InterruptedException =>
          e
    (caught != null) shouldBe true
    caught.getMessage shouldBe "user pressed Ctrl-C"
    task.state shouldBe Task.State.Cancelled
  }

  test("lookup of an unregistered id fails the worker, surfaced via await") {
    skipIfNotNode()
    val task   = Task.runRegistered("definitely-not-registered")
    val caught =
      try
        task.await()
        null
      catch
        case e: Throwable =>
          e
    (caught != null) shouldBe true
    // Either the parent-side worker-exited synthesis or the worker-side NoSuchElementException
    // surfaces — both are acceptable; we just need a terminal Failed.
    task.state shouldBe Task.State.Failed
  }

end NodeWorkerTaskTest
