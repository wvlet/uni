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
  * A synchronization primitive representing a single value that may not yet be available.
  *
  * RxDeferred is inspired by cats-effect Deferred. When created, it is empty. It can be completed
  * exactly once with a value, after which all waiting Rx streams will receive that value.
  *
  * Key properties:
  *   - Can only be completed once (subsequent completions return false)
  *   - `get` semantically blocks (suspends) until a value is available
  *   - Safe for concurrent access from multiple threads
  *   - Completion triggers all waiting callbacks
  *
  * @tparam A
  *   the type of the value
  */
trait RxDeferred[A]:

  /**
    * Get the value, semantically blocking until it becomes available. This does not actually block
    * an OS thread; instead, it suspends the Rx computation.
    */
  def get: Rx[A]

  /**
    * Complete this deferred with a value. Returns true if this was the first completion, false if
    * already completed.
    */
  def complete(a: A): Rx[Boolean]

  /**
    * Complete this deferred with an error. Returns true if this was the first completion, false if
    * already completed.
    */
  def completeWithError(e: Throwable): Rx[Boolean]

  /**
    * Try to get the current value without blocking. Returns None if not yet completed.
    */
  def tryGet: Rx[Option[A]]

  /**
    * Check if this deferred has been completed.
    */
  def isCompleted: Rx[Boolean]

object RxDeferred:

  /**
    * Create a new empty RxDeferred.
    */
  def apply[A](): RxDeferred[A] = new RxDeferredImpl[A]()

  /**
    * Create a new empty RxDeferred wrapped in Rx.
    */
  def of[A](): Rx[RxDeferred[A]] = Rx.single(apply[A]())

  /**
    * Internal state representation
    */
  private sealed trait State[A]
  private case class Empty[A](waiters: Queue[Try[A] => Unit]) extends State[A]
  private case class Completed[A](result: Try[A])             extends State[A]

  private class RxDeferredImpl[A]() extends RxDeferred[A]:
    private val state = new AtomicReference[State[A]](Empty(Queue.empty))

    override def get: Rx[A] = Rx.AsyncOp[A](
      callback => registerLoop(callback).foreach(callback),
      "RxDeferred.get"
    )

    @tailrec
    private def registerLoop(callback: Try[A] => Unit): Option[Try[A]] =
      state.get() match
        case Completed(result) =>
          Some(result)
        case empty @ Empty(waiters) =>
          val newState = Empty(waiters.enqueue(callback))
          if state.compareAndSet(empty, newState) then
            None
          else
            registerLoop(callback)

    override def complete(a: A): Rx[Boolean] = Rx.single {
      completeInternal(Success(a))
    }

    override def completeWithError(e: Throwable): Rx[Boolean] = Rx.single {
      completeInternal(Failure(e))
    }

    @tailrec
    private def completeInternal(result: Try[A]): Boolean =
      state.get() match
        case Completed(_) =>
          false
        case empty @ Empty(waiters) =>
          if state.compareAndSet(empty, Completed(result)) then
            // Notify all waiters
            waiters.foreach(callback => callback(result))
            true
          else
            completeInternal(result)

    override def tryGet: Rx[Option[A]] = Rx.single {
      state.get() match
        case Completed(Success(a)) =>
          Some(a)
        case _ =>
          None
    }

    override def isCompleted: Rx[Boolean] = Rx.single {
      state.get().isInstanceOf[Completed[?]]
    }

    // Unsafe method for direct access
    private[rx] def unsafeTryGet: Option[Try[A]] =
      state.get() match
        case Completed(result) =>
          Some(result)
        case Empty(_) =>
          None

  end RxDeferredImpl

end RxDeferred
