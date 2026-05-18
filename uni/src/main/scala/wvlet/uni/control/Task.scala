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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import wvlet.uni.rx.Rx
import wvlet.uni.rx.compat as rxCompat

/**
  * A cross-platform handle to a unit of background work that can be cooperatively cancelled and
  * waited on from another thread (or, on Scala.js, from another event-loop turn).
  *
  * The body is `TaskContext => Unit`. There is no `[A]` result type: the body's intentional output
  * — progress, results, partial values — is published through caller-supplied channels (`RxVar`,
  * `RxQueue`, `RxDeferred`, …). `Task`'s job is the lifecycle: start, cancel, observe terminal
  * state.
  *
  * Two scheduling entry points:
  *   - [[Task.run]] with a closure — natural on JVM/Native, microtask-scheduled on Scala.js. On
  *     Scala.js `await()` throws (would deadlock the event loop) — use `awaitRx`.
  *   - [[Task.runRegistered]] with a body id from [[TaskRegistry]] — required for blocking
  *     `await()` on Scala.js + Node, where the body runs in a `worker_threads` worker.
  *
  * Platform notes:
  *   - JVM / Scala Native run the body on a fresh daemon thread, so `await()` blocks the caller
  *     until the body returns. Cancel additionally `Thread.interrupt`s so blocking JDK calls
  *     (`Thread.sleep`, blocking IO) unwedge.
  *   - Scala.js `Task.run { body }` runs cooperatively on the event-loop microtask queue; `await()`
  *     is unsupported (would deadlock), use `awaitRx`.
  *   - Scala.js `Task.runRegistered("id")` on Node spawns a `worker_threads` worker so the main
  *     thread can `Atomics.wait` — blocking `await()` works. On the browser this falls back to the
  *     microtask path (no main-thread `Atomics.wait`); use `awaitRx`.
  *
  * See `plans/2026-05-18-cross-platform-thread.md` and uni#552 for the design rationale.
  */
trait Task:

  /** Current observable state. */
  def state: Task.State

  /** True once the state is terminal (Succeeded / Failed / Cancelled). Non-blocking. */
  def isDone: Boolean = state.isTerminal

  /**
    * Request cooperative cancellation. Idempotent. Returns immediately. The body observes the
    * request via [[TaskContext.isCancelled]] / [[TaskContext.checkCancelled]] at safe points; the
    * optional `reason` is carried into the `InterruptedException` that `checkCancelled` throws and
    * that `await()` rethrows, so it surfaces in stack traces and `awaitRx` error events.
    *
    * Only the first call records a reason; subsequent calls are no-ops.
    */
  def cancel(reason: String = ""): Unit

  /**
    * Block the calling thread until the task reaches a terminal state.
    *
    *   - On JVM / Scala Native: blocks; rethrows the body's exception on `Failed`; throws
    *     `InterruptedException` on `Cancelled`.
    *   - On Scala.js + Node via [[Task.runRegistered]]: blocks the main thread on `Atomics.wait`
    *     while the body runs in a `worker_threads` worker; rethrows / throws as above.
    *   - On Scala.js via [[Task.run]] (microtask) or on browser: throws
    *     `UnsupportedOperationException` — use [[awaitRx]] instead.
    */
  def await(): Unit

  /**
    * Rx-driven completion. Emits `OnNext(())` then `OnCompletion` on success; `OnError` on failure
    * or cancellation. Works on every platform including Scala.js.
    */
  def awaitRx: Rx[Unit]

end Task

object Task:

  enum State:
    case Running,
      Succeeded,
      Failed,
      Cancelled

    def isTerminal: Boolean = this != Running

  /**
    * Run `body` as a background task. The body receives a [[TaskContext]] it can use to check for
    * cancellation. See [[Task]] for platform-specific scheduling notes.
    */
  def run(body: TaskContext => Unit): Task = taskCompat.run(body)

  /**
    * Run a body previously registered with [[TaskRegistry.register]]. On Node, the body runs in a
    * `worker_threads` worker so blocking `await()` works; on JVM / Native / browser this behaves
    * the same as `run` with the looked-up body.
    */
  def runRegistered(taskId: String): Task = taskCompat.runRegistered(taskId)

end Task

/**
  * Named-body registry for [[Task.runRegistered]].
  *
  * Separate from [[Task]] because the two concerns are different: `Task` represents *a handle to a
  * running task*; `TaskRegistry` holds *templates of work that can be looked up by id*. They happen
  * to be linked through `runRegistered` but otherwise share no state.
  *
  * **Instantiable for tests.** A fresh `TaskRegistry()` gives a clean, isolated map — useful for
  * unit tests that need to verify registration / lookup without leaking into other tests. The
  * production-default singleton is [[TaskRegistry.default]] and is what [[Task.runRegistered]]
  * uses; the companion's `register` is a shortcut for `default.register`. This split keeps tests
  * isolatable without breaking the Node worker-thread mechanism, which requires a globally findable
  * registry per isolate (see below).
  *
  * **Why a registry exists at all.** On Scala.js + Node, blocking `Task.await()` requires running
  * the body in a `worker_threads` worker (the main thread can't use `Atomics.wait` while also
  * hosting the body via the event loop — that would deadlock). Worker threads are separate V8
  * isolates with no shared closures, so the body cannot be passed by reference; the worker has to
  * dynamically import the Scala.js bundle into its own isolate and look up the body in
  * [[TaskRegistry.default]] there. JVM and Native have no such constraint, but the API is uniform
  * across platforms so callers can write one body and run it anywhere.
  *
  * **Registration must run at module-init time** (when using [[TaskRegistry.default]] for
  * Node-blocking-await use cases). Scala.js singletons initialise lazily, but the worker's bundle
  * re-import only re-runs module-init code. Place `TaskRegistry.register` calls inside an `object`
  * body and ensure the object is eagerly initialised (typically via a `@JSExportTopLevel val`
  * marker, or by being referenced from one).
  */
class TaskRegistry:

  private val bodies = scala.collection.mutable.HashMap.empty[String, TaskContext => Unit]

  /**
    * Register a task body under a stable id. If the id was previously registered, the new body
    * replaces the old one.
    */
  def register(taskId: String)(body: TaskContext => Unit): Unit = bodies.synchronized {
    bodies.update(taskId, body)
  }

  /**
    * Look up a registered body. Throws `NoSuchElementException` if `taskId` isn't registered in
    * this registry (most commonly because the registering object hasn't been initialised yet — see
    * the companion's class scaladoc for the module-init pattern).
    */
  def lookup(taskId: String): TaskContext => Unit = bodies.synchronized {
    bodies.getOrElse(
      taskId,
      throw new NoSuchElementException(s"No task registered with id '${taskId}'")
    )
  }

  /** True iff a body is registered under this id. Non-throwing. */
  def isRegistered(taskId: String): Boolean = bodies.synchronized {
    bodies.contains(taskId)
  }

end TaskRegistry

object TaskRegistry:

  /** Create a fresh, isolated registry. Mainly useful for unit tests. */
  def apply(): TaskRegistry = new TaskRegistry()

  /**
    * The process-default registry. [[Task.runRegistered]] always looks up bodies here, including
    * the Scala.js worker-thread implementation (the worker reads `TaskRegistry.default` in its own
    * isolate, repopulated by the worker's bundle re-import).
    */
  val default: TaskRegistry = new TaskRegistry()

  /** Shortcut for `TaskRegistry.default.register(taskId)(body)`. */
  def register(taskId: String)(body: TaskContext => Unit): Unit = default.register(taskId)(body)

end TaskRegistry

/**
  * The handle passed to the body of [[Task.run]] so the body can check cancellation without
  * referencing the outer `Task`.
  */
trait TaskContext:

  /** True iff cancellation has been requested. Check at safe points in the body. */
  def isCancelled: Boolean

  /**
    * Throws `InterruptedException` carrying the cancel reason if cancellation has been requested;
    * no-op otherwise. Default message ("Task cancelled") is used when `cancel()` was called without
    * a reason.
    */
  def checkCancelled(): Unit

end TaskContext

/**
  * Shared state machine for all platform implementations of [[Task]].
  *
  * Subclasses plug in the platform-specific scheduling (`scheduleBody`) and the blocking behaviour
  * of [[Task.await]] (`awaitTerminal`). Cancel flag, terminal-state transition, and the Rx
  * completion future live here so every platform behaves identically.
  */
private[control] abstract class TaskImpl extends Task with TaskContext:

  private val stateRef   = new AtomicReference[Task.State](Task.State.Running)
  private val cancelFlag = new AtomicBoolean(false)
  private val completion = Promise[Unit]()
  // Cause captured on Failed / Cancelled; rethrown by await(). Null on success.
  @volatile
  private var failure: Throwable = null

  // Recorded on the first `cancel(reason)` call; read by checkCancelled. Null until cancelled.
  @volatile
  private var cancelReason: String = null

  // Memoised so repeated `awaitRx` calls share the same Rx instance (and the same Promise→Rx
  // subscription chain underneath).
  private lazy val cachedAwaitRx: Rx[Unit] =
    Rx.future(completion.future)(using rxCompat.defaultExecutionContext)

  final override def state: Task.State    = stateRef.get()
  final override def isCancelled: Boolean = cancelFlag.get()
  final override def awaitRx: Rx[Unit]    = cachedAwaitRx
  final override def await(): Unit        = awaitTerminal()

  final override def cancel(reason: String = ""): Unit =
    if cancelFlag.compareAndSet(false, true) then
      cancelReason = reason
      onCancelRequested()

  final override def checkCancelled(): Unit =
    if isCancelled then
      val msg =
        if cancelReason == null || cancelReason.isEmpty then
          "Task cancelled"
        else
          cancelReason
      throw new InterruptedException(msg)

  protected def scheduleBody(body: TaskContext => Unit): Unit
  protected def awaitTerminal(): Unit
  protected def onCancelRequested(): Unit = ()

  /**
    * Invoke `body` with this context, then transition into the terminal state and complete the Rx
    * future. Subclasses call this from inside `scheduleBody` on whichever worker they own.
    */
  protected final def runBody(body: TaskContext => Unit): Unit =
    try
      body(this)
      stateRef.set(Task.State.Succeeded)
      completion.success(())
    catch
      case e: Throwable if cancelFlag.get() =>
        // Cancel was requested before the body finished — classify the throw as Cancelled and
        // ensure the surfaced exception is the canonical `InterruptedException` (carrying the
        // cancel reason). If the body itself threw something else (e.g. a JDBC
        // `InterruptedIOException` from a cancelled query), attach it as the cause so debugging
        // info isn't lost.
        stateRef.set(Task.State.Cancelled)
        val ie =
          e match
            case ie: InterruptedException =>
              ie
            case other =>
              val wrapped =
                new InterruptedException(
                  if cancelReason == null || cancelReason.isEmpty then
                    "Task cancelled"
                  else
                    cancelReason
                )
              wrapped.initCause(other)
              wrapped
        failure = ie
        completion.failure(ie)
      case e: Throwable =>
        stateRef.set(Task.State.Failed)
        failure = e
        completion.failure(e)

  /** For subclasses' `awaitTerminal` to rethrow on JVM/Native. Null on Succeeded. */
  protected final def terminalFailure: Throwable = failure

end TaskImpl
