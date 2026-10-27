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
package wvlet.uni.rx

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
  * A lightweight fiber representing a running computation.
  *
  * RxFiber provides structured concurrency primitives similar to cats-effect Fiber. It allows
  * forking a computation to run concurrently, joining to wait for its result, and cancellation.
  *
  * Unlike OS threads, fibers are very lightweight and can be created in large numbers without
  * significant overhead.
  *
  * @tparam A
  *   the result type of the fiber
  */
trait RxFiber[A]:

  /**
    * Wait for the fiber to complete and return its result. If the fiber failed, the error is
    * propagated.
    */
  def join: Rx[A]

  /**
    * Request cancellation of this fiber. The fiber may not be immediately cancelled if it is in the
    * middle of an operation.
    */
  def cancel: Rx[Unit]

  /**
    * Check if the fiber has completed without blocking. Returns None if still running, Some(result)
    * if completed.
    */
  def poll: Rx[Option[Try[A]]]

  /**
    * Check if the fiber has been cancelled.
    */
  def isCancelled: Rx[Boolean]

object RxFiber:

  /**
    * Internal state of a fiber
    */
  private sealed trait FiberState[A]
  private case class Running[A](joiners: Queue[Try[A] => Unit], cancellers: Queue[() => Unit])
      extends FiberState[A]

  private case class Completed[A](result: Try[A]) extends FiberState[A]
  private case class Cancelled[A]()               extends FiberState[A]

  /**
    * Create a fiber from an Rx computation.
    */
  private[rx] def start[A](rx: Rx[A], scheduler: RxScheduler): RxFiber[A] =
    val fiber = new RxFiberImpl[A]()

    // Run the computation on the scheduler
    scheduler.execute(() =>
      val cancelable =
        RxRunner.runOnce(rx) {
          case OnNext(v) =>
            fiber.complete(Success(v.asInstanceOf[A]))
          case OnError(e) =>
            fiber.complete(Failure(e))
          case OnCompletion =>
            fiber.complete(
              Failure(new IllegalStateException("Fiber completed without producing a value"))
            )
        }
      fiber.setCancelable(cancelable)
    )

    fiber

  private class RxFiberImpl[A] extends RxFiber[A]:
    private val state      = new AtomicReference[FiberState[A]](Running(Queue.empty, Queue.empty))
    private var cancelable = Cancelable.empty

    private[rx] def setCancelable(c: Cancelable): Unit = cancelable = c

    override def join: Rx[A] = Rx.AsyncOp[A](
      callback => registerJoinLoop(callback).foreach(callback),
      "RxFiber.join"
    )

    @tailrec
    private def registerJoinLoop(callback: Try[A] => Unit): Option[Try[A]] =
      state.get() match
        case Completed(result) =>
          Some(result)
        case Cancelled() =>
          Some(Failure(new InterruptedException("Fiber was cancelled")))
        case running @ Running(joiners, cancellers) =>
          val newState = Running(joiners.enqueue(callback), cancellers)
          if state.compareAndSet(running, newState) then
            None
          else
            registerJoinLoop(callback)

    override def cancel: Rx[Unit] = Rx.single {
      cancelLoop()
    }

    @tailrec
    private def cancelLoop(): Unit =
      state.get() match
        case Completed(_) | Cancelled() =>
          () // Already done, nothing to cancel
        case running @ Running(joiners, cancellers) =>
          if state.compareAndSet(running, Cancelled()) then
            // Cancel the underlying computation
            cancelable.cancel

            // Notify all joiners that we were cancelled
            val error = Failure(new InterruptedException("Fiber was cancelled"))
            joiners.foreach(callback => callback(error))

            // Notify cancellation listeners
            cancellers.foreach(callback => callback())
          else
            cancelLoop() // Retry on CAS failure

    private[rx] def complete(result: Try[A]): Unit = completeLoop(result)

    @tailrec
    private def completeLoop(result: Try[A]): Unit =
      state.get() match
        case Completed(_) | Cancelled() =>
          () // Already done
        case running @ Running(joiners, _) =>
          if state.compareAndSet(running, Completed(result)) then
            // Notify all joiners
            joiners.foreach(callback => callback(result))
          else
            completeLoop(result)

    override def poll: Rx[Option[Try[A]]] = Rx.single {
      state.get() match
        case Completed(result) =>
          Some(result)
        case Cancelled() =>
          Some(Failure(new InterruptedException("Fiber was cancelled")))
        case Running(_, _) =>
          None
    }

    override def isCancelled: Rx[Boolean] = Rx.single {
      state.get().isInstanceOf[Cancelled[?]]
    }

  end RxFiberImpl

end RxFiber

/**
  * Extension methods for Rx to support fiber-based concurrency.
  */
extension [A](rx: Rx[A])

  /**
    * Fork this Rx to run concurrently, returning a fiber handle.
    */
  def start: Rx[RxFiber[A]] = startOn(RxScheduler.default)

  /**
    * Fork this Rx to run on the specified scheduler.
    */
  def startOn(scheduler: RxScheduler): Rx[RxFiber[A]] = Rx.single {
    RxFiber.start(rx, scheduler)
  }

  /**
    * Fork and forget - run this Rx concurrently without waiting for the result.
    */
  def startAndForget: Rx[Unit] = Rx.single {
    RxFiber.start(rx, RxScheduler.default)
    ()
  }

  /**
    * Run this Rx on the blocking scheduler, suitable for blocking I/O operations.
    */
  def evalOnBlocking: Rx[A] = Rx.single(RxFiber.start(rx, RxScheduler.blocking)).flatMap(_.join)
