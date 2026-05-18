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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxVar
import wvlet.uni.rx.compat as rxCompat

/**
  * Shared state machine for all platform implementations of [[Task]].
  *
  * Subclasses plug in the platform-specific scheduling (`scheduleBody`) and the blocking behaviour
  * of [[Task.await]] (`awaitTerminal`). Everything else — cancel flag, progress storage, Rx
  * completion — lives here so the contract is identical across JVM, Scala.js, and Scala Native.
  */
private[concurrent] abstract class TaskImpl extends Task with TaskContext:

  private val stateRef    = new AtomicReference[Task.State](Task.State.Running)
  private val cancelFlag  = new AtomicBoolean(false)
  private val progressRef = new AtomicReference[Option[Task.Progress]](None)
  // Holds the most recent progress emission so subscribers get an Rx event per call to
  // reportProgress. Push via `:= Some(p)` with force=true; clients filter Some + map.
  private val progressVar = new RxVar[Option[Task.Progress]](None)
  private val completion  = Promise[Unit]()
  // Captured cause for failure/cancellation; rethrown by await().
  @volatile
  private var failure: Throwable = null

  // ---- Task API ----

  final override def state: Task.State                 = stateRef.get()
  final override def progress: Option[Task.Progress]   = progressRef.get()
  final override def progressStream: Rx[Task.Progress] = progressVar.filter(_.isDefined).map(_.get)
  final override def awaitRx: Rx[Unit]                 =
    Rx.future(completion.future)(using rxCompat.defaultExecutionContext)

  final override def cancel(): Unit =
    if cancelFlag.compareAndSet(false, true) then
      stateRef.compareAndSet(Task.State.Running, Task.State.Cancelling)
      onCancelRequested()

  final override def await(): Unit = awaitTerminal()

  // ---- TaskContext API ----

  final override def isCancelled: Boolean = cancelFlag.get()

  final override def reportProgress(p: Task.Progress): Unit =
    progressRef.set(Some(p))
    progressVar.update(_ => Some(p), force = true)

  // ---- Subclass hooks ----

  /**
    * Called once by [[start]] to dispatch `body` on whichever platform-specific worker the subclass
    * chooses (OS thread, microtask, …).
    */
  protected def scheduleBody(body: TaskContext => Unit): Unit

  /** Block (JVM/Native) or throw `UnsupportedOperationException` (Scala.js). */
  protected def awaitTerminal(): Unit

  /** Called once when cancel transitions the flag from false to true. JVM may interrupt here. */
  protected def onCancelRequested(): Unit = ()

  /** Entry point invoked by `taskCompat.run` once construction is complete. */
  private[concurrent] final def start(body: TaskContext => Unit): Unit = scheduleBody(body)

  // ---- Body runner ----

  /**
    * Invoke `body` with this context, then transition into the terminal state and notify the
    * progress stream + completion future. Subclasses call this from inside `scheduleBody` on the
    * worker thread / event-loop turn they own.
    */
  protected final def runBody(body: TaskContext => Unit): Unit =
    val outcome: Either[Throwable, Unit] =
      try
        body(this)
        if cancelFlag.get() then
          Left(new InterruptedException("Task cancelled"))
        else
          Right(())
      catch
        case e: InterruptedException =>
          // Body observed cooperative cancel and threw. Normalise to Cancelled.
          Left(e)
        case e: Throwable =>
          Left(e)

    outcome match
      case Right(_) =>
        stateRef.set(Task.State.Succeeded)
        progressVar.stop()
        completion.success(())
      case Left(e) if cancelFlag.get() =>
        stateRef.set(Task.State.Cancelled)
        failure = e
        // Stream completes cleanly; failure is reported via awaitRx / await().
        progressVar.stop()
        completion.failure(e)
      case Left(e) =>
        stateRef.set(Task.State.Failed)
        failure = e
        progressVar.setException(e)
        completion.failure(e)
  end runBody

  /** For subclasses' `awaitTerminal` to rethrow on JVM/Native. */
  protected final def terminalFailure: Throwable = failure

end TaskImpl
