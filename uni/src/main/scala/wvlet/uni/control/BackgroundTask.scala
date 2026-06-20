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

import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.util.Result

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Thrown by [[TaskContext.checkCancelled]] when the task has been cancelled. */
final class TaskCancelledException extends RuntimeException("BackgroundTask was cancelled")

/**
  * Handed to a [[BackgroundTask]] body so it can observe cancellation and report progress. The body
  * is a plain side-effecting block (no effect monad); it cooperates with cancellation by polling
  * [[isCancelled]] / calling [[checkCancelled]] at safe points (e.g. loop iterations).
  */
trait TaskContext[P]:
  /** True once [[BackgroundTask.cancel]] has been called. */
  def isCancelled: Boolean

  /** Throw [[TaskCancelledException]] if cancelled — call at safe points to abort cooperatively. */
  def checkCancelled(): Unit

  /** Publish the latest progress, readable by the caller via [[BackgroundTask.progress]]. */
  def reportProgress(p: P): Unit

  /**
    * Register a hook run (on the caller's thread) when [[BackgroundTask.cancel]] is invoked — the
    * escape hatch for interrupting a call already in flight (e.g. `duckdb_interrupt`). A hook
    * registered after cancellation runs immediately.
    */
  def onCancel(hook: () => Unit): Unit

/**
  * A unit of work running on a background worker: cooperatively cancellable and progress-pollable
  * from another thread.
  *
  * On JVM and Native the body runs on a daemon thread, so the caller can poll progress / cancel /
  * block on [[await]] concurrently. On Scala.js there are no threads, so the body runs inline
  * during [[BackgroundTask.start]] and completes before it returns — the API is identical, but
  * there is no concurrency (and [[cancel]] after completion is a no-op).
  */
trait BackgroundTask[A, P]:
  /** Latest progress the body reported, or `None` if none yet. Non-blocking. */
  def progress: Option[P]

  /**
    * A push stream of progress updates that completes when the task finishes. On JVM/Native it is a
    * live feed; on Scala.js the body runs inline during [[BackgroundTask.start]], so a subscriber
    * attached afterwards sees only the final reported value (not a live feed) — use [[progress]]
    * there.
    */
  def progressStream: Rx[P]

  /** Terminal snapshot: `None` while running, `Some(result)` once finished/failed. Non-blocking. */
  def poll: Option[Result[A]]

  /** Block the calling thread until the task terminates, returning its result. */
  def await(): Result[A]

  /** Signal cooperative cancellation: sets the flag and runs any registered `onCancel` hooks. */
  def cancel(): Unit

  def isCancelled: Boolean
  def isDone: Boolean

/** One-shot completion gate, provided per platform (JVM/Native: a latch; JS: a no-op). */
private[control] trait Gate:
  /** Block until [[signal]] has been called (returns immediately if already signalled). */
  def await(): Unit
  def signal(): Unit

object BackgroundTask:

  /**
    * Start `body` on a background worker (JVM/Native: a daemon thread; JS: inline). The body
    * receives a [[TaskContext]] for cooperative cancellation and progress reporting.
    */
  def start[A, P](body: TaskContext[P] => A): BackgroundTask[A, P] =
    val task = BackgroundTaskImpl[A, P](body)
    task.launch()
    task

/**
  * Shared state machine. The only platform-specific pieces are spawning the worker and the
  * completion gate (see `BackgroundTaskCompat`).
  */
private[control] class BackgroundTaskImpl[A, P](body: TaskContext[P] => A)
    extends BackgroundTask[A, P]
    with TaskContext[P]:

  private val cancelled = AtomicBoolean(false)
  // progressRef is the source of truth for the non-blocking snapshot: RxVar.get reads a non-volatile
  // var outside any lock, so it is not safe for cross-thread polling. progressVar is only the push
  // notification channel for progressStream (its propagateEvent is synchronized = thread-safe).
  private val progressRef = AtomicReference[Option[P]](None)
  private val progressVar = RxVar[Option[P]](None)
  private val resultRef   = AtomicReference[Option[Result[A]]](None)
  private val gate        = BackgroundTaskCompat.newGate()

  // onCancel hooks are coordinated under a lock (low-frequency): registration races cancellation.
  private val hookLock                = Object()
  private var hooks: List[() => Unit] = Nil
  private var hooksDrained: Boolean   = false

  /** Start the worker. Called after construction so `this` does not escape mid-initialization. */
  private[control] def launch(): Unit = BackgroundTaskCompat.runWorker { () =>
    try
      // Catch every Throwable (not just NonFatal): a fatal error or InterruptedException must still
      // become a result, or `await` would hang on the gate forever. (Inline on JS, so we record the
      // failure rather than rethrow — that keeps `start` from throwing there.)
      val r =
        try
          Result.Success(body(this))
        catch
          case e: Throwable =>
            Result.Failure(e)
      resultRef.set(Some(r))
    finally
      // The task is finished: drain the cancel hooks so a later cancel() can't fire them against an
      // unrelated operation, and release their captured closures. No-op if cancel() already drained.
      hookLock.synchronized {
        hooksDrained = true
        hooks = Nil
      }
      // gate.signal() must run even if a progressStream subscriber throws during completion, or
      // await() would hang forever — so complete the stream inside a try whose finally signals.
      try progressVar.stop() // complete the progress stream
      finally gate.signal()
  }

  // --- TaskContext (body side) ---

  override def isCancelled: Boolean = cancelled.get()

  override def checkCancelled(): Unit =
    if cancelled.get() then
      throw TaskCancelledException()

  override def reportProgress(p: P): Unit =
    progressRef.set(Some(p)) // visibility-safe snapshot
    progressVar.set(Some(p)) // notify progressStream subscribers

  override def onCancel(hook: () => Unit): Unit =
    val runNow = hookLock.synchronized {
      if hooksDrained then
        // Drained by cancel() → run now; drained by normal completion → don't (task already done).
        cancelled.get()
      else
        hooks = hook :: hooks
        false
    }
    if runNow then
      runHookQuietly(hook)

  // --- BackgroundTask (caller side) ---

  override def progress: Option[P] = progressRef.get()
  // flatMap (not filter): the initial replayed None maps to Rx.empty, which flatMap skips — whereas
  // a false `filter` predicate emits OnCompletion downstream (RxRunner FilterOp), prematurely
  // ending the stream. RxVar holds only the latest value, so unconsumed progress doesn't accumulate.
  override def progressStream: Rx[P] = progressVar.flatMap {
    case Some(p) =>
      Rx.single(p)
    case None =>
      Rx.empty
  }

  override def poll: Option[Result[A]] = resultRef.get()
  override def isDone: Boolean         = resultRef.get().isDefined

  override def await(): Result[A] =
    gate.await()
    // The worker sets resultRef before signalling the gate, so it is visible here.
    resultRef.get().get

  override def cancel(): Unit =
    cancelled.set(true)
    val toRun = hookLock.synchronized {
      if hooksDrained then
        Nil
      else
        hooksDrained = true
        val h = hooks
        hooks = Nil
        h
    }
    // Registered newest-first; run in registration order.
    toRun.reverse.foreach(runHookQuietly)

  private def runHookQuietly(hook: () => Unit): Unit =
    try
      hook()
    catch
      case NonFatal(_) =>
        ()

end BackgroundTaskImpl
