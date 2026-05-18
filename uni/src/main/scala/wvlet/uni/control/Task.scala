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
  *   - Scala.js runs the body cooperatively on the event loop. `await()` is unsupported
  *     (`UnsupportedOperationException`) — use `awaitRx` instead.
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
    * request via [[TaskContext.isCancelled]] / [[TaskContext.checkCancelled]] at safe points.
    */
  def cancel(): Unit

  /**
    * Block the calling thread until the task reaches a terminal state.
    *
    *   - On JVM / Scala Native: blocks; rethrows the body's exception on `Failed`; throws
    *     `InterruptedException` on `Cancelled`.
    *   - On Scala.js: throws `UnsupportedOperationException` — use [[awaitRx]] instead.
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

end Task

/**
  * The handle passed to the body of [[Task.run]] so the body can check cancellation without
  * referencing the outer `Task`.
  */
trait TaskContext:

  /** True iff cancellation has been requested. Check at safe points in the body. */
  def isCancelled: Boolean

  /** Throws `InterruptedException` if cancellation has been requested; no-op otherwise. */
  def checkCancelled(): Unit =
    if isCancelled then
      throw new InterruptedException("Task cancelled")

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

  // Memoised so repeated `awaitRx` calls share the same Rx instance (and the same Promise→Rx
  // subscription chain underneath).
  private lazy val cachedAwaitRx: Rx[Unit] =
    Rx.future(completion.future)(using rxCompat.defaultExecutionContext)

  final override def state: Task.State    = stateRef.get()
  final override def isCancelled: Boolean = cancelFlag.get()
  final override def awaitRx: Rx[Unit]    = cachedAwaitRx
  final override def await(): Unit        = awaitTerminal()

  final override def cancel(): Unit =
    if cancelFlag.compareAndSet(false, true) then
      onCancelRequested()

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
