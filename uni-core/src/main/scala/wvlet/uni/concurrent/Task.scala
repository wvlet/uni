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

import wvlet.uni.rx.Rx

/**
  * A cross-platform handle to a unit of background work that can be cooperatively cancelled and
  * progress-polled from another thread (or, on Scala.js, from another event-loop turn).
  *
  * The body is `TaskContext => Unit`. There is no `[A]` result type: the body's intentional output
  * is published through caller-supplied channels (`RxDeferred`, `RxVar`, a queue) using the same
  * mechanism as progress reporting. `Task`'s job is the lifecycle — start, cancel, observe terminal
  * state.
  *
  * Platform notes:
  *   - JVM / Scala Native run the body on a fresh daemon thread, so `await()` blocks the caller
  *     until the body returns.
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

  /** Latest progress snapshot, or `None` if the body has not reported any yet. */
  def progress: Option[Task.Progress]

  /**
    * Push-style progress stream. Each `reportProgress` call yields one `OnNext`. The stream
    * completes (or errors) when the task reaches its terminal state.
    */
  def progressStream: Rx[Task.Progress]

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
    *   - On Scala.js: throws `UnsupportedOperationException` (the JS event loop cannot block). Use
    *     [[awaitRx]] from a non-blocking context instead.
    */
  def await(): Unit

  /**
    * Rx-driven completion. Emits `OnNext(())` then `OnCompletion` on success; `OnError` on failure
    * or cancellation. Works on every platform including Scala.js, and is the recommended universal
    * completion mechanism.
    */
  def awaitRx: Rx[Unit]

end Task

object Task:

  /**
    * Caller-defined progress payload. `Task` provides no semantics beyond "latest snapshot wins."
    */
  type Progress = Any

  enum State:
    case Running,
      Cancelling,
      Succeeded,
      Failed,
      Cancelled

    def isTerminal: Boolean =
      this match
        case Succeeded | Failed | Cancelled =>
          true
        case _ =>
          false

  /**
    * Run `body` as a background task. The body receives a [[TaskContext]] it can use to check for
    * cancellation and report progress. See [[Task]] for platform-specific scheduling notes.
    */
  def run(body: TaskContext => Unit): Task = taskCompat.run(body)

end Task

/**
  * The handle passed to the body of [[Task.run]] so the body can check cancellation and publish
  * progress without referencing the outer `Task`.
  */
trait TaskContext:

  /** True iff cancellation has been requested. Check at safe points in the body. */
  def isCancelled: Boolean

  /** Throws `InterruptedException` if cancellation has been requested; no-op otherwise. */
  def checkCancelled(): Unit =
    if isCancelled then
      throw new InterruptedException("Task cancelled")

  /**
    * Publish a progress snapshot. Cheap; safe to call from a tight loop. The latest snapshot is
    * returned by [[Task.progress]]; every call is also emitted to [[Task.progressStream]].
    */
  def reportProgress(p: Task.Progress): Unit

end TaskContext
