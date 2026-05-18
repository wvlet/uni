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

  // ---- Registry-based bodies ----
  // Why a registry: on Scala.js + Node, blocking `Task.await()` requires running the body in a
  // `worker_threads` worker so the main thread can use `Atomics.wait` (deadlocks otherwise — see
  // plans/2026-05-18-cross-platform-thread.md). Closures can't be transferred across V8 isolates,
  // so the worker has to look up the body by name in its own Scala.js bundle re-import. JVM and
  // Native have no such constraint, but the API is uniform across platforms so callers can write
  // one body and run it anywhere.

  private val registry = scala.collection.mutable.HashMap.empty[String, TaskContext => Unit]

  /**
    * Register a task body under a stable id. Required for Node-only blocking `await()` via
    * `runRegistered`. Bodies should be registered at module-init time (inside an `object` body) so
    * the worker's bundle re-import re-populates the registry in the worker isolate.
    */
  def register(taskId: String)(body: TaskContext => Unit): Unit = registry.synchronized {
    registry.update(taskId, body)
  }

  /**
    * Look up a registered body. Throws `NoSuchElementException` if `taskId` isn't registered in the
    * current isolate (most commonly because the registering object hasn't been initialised yet —
    * see [[register]] for the module-init pattern).
    */
  def lookup(taskId: String): TaskContext => Unit = registry.synchronized {
    registry.getOrElse(
      taskId,
      throw new NoSuchElementException(s"No task registered with id '${taskId}'")
    )
  }

  /**
    * Run a previously-registered body. On Node, the body runs in a `worker_threads` worker so
    * blocking `await()` works; on JVM / Native / browser this behaves the same as `run` with the
    * looked-up body.
    */
  def runRegistered(taskId: String): Task = taskCompat.runRegistered(taskId)

end Task

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
        // Cancel was requested before the body finished — classify the throw as Cancelled even
        // if the exception type would otherwise be Failed (e.g. a JDBC InterruptedIOException).
        stateRef.set(Task.State.Cancelled)
        failure = e
        completion.failure(e)
      case e: Throwable =>
        stateRef.set(Task.State.Failed)
        failure = e
        completion.failure(e)

  /** For subclasses' `awaitTerminal` to rethrow on JVM/Native. Null on Succeeded. */
  protected final def terminalFailure: Throwable = failure

end TaskImpl
