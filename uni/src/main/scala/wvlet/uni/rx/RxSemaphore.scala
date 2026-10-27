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
import scala.util.Success
import scala.util.Try

/**
  * A semaphore for controlling concurrent access to a limited resource.
  *
  * RxSemaphore is inspired by cats-effect Semaphore. It maintains a count of available permits.
  * `acquire` decrements the count (blocking if zero), and `release` increments it.
  *
  * Key properties:
  *   - Permits can be acquired and released in any order
  *   - `acquire` semantically blocks (suspends) if no permits are available
  *   - Safe for concurrent access from multiple threads
  *   - No thread actually blocks; waiting is cooperative
  */
trait RxSemaphore:

  /**
    * Acquire a permit, semantically blocking if none are available.
    */
  def acquire: Rx[Unit]

  /**
    * Acquire n permits, semantically blocking if not enough are available.
    */
  def acquireN(n: Long): Rx[Unit]

  /**
    * Release a permit.
    */
  def release: Rx[Unit]

  /**
    * Release n permits.
    */
  def releaseN(n: Long): Rx[Unit]

  /**
    * Try to acquire a permit without blocking. Returns true if successful.
    */
  def tryAcquire: Rx[Boolean]

  /**
    * Try to acquire n permits without blocking. Returns true if successful.
    */
  def tryAcquireN(n: Long): Rx[Boolean]

  /**
    * Execute an Rx with automatic acquire before and release after.
    */
  def withPermit[A](rx: Rx[A]): Rx[A]

  /**
    * Execute an Rx with automatic acquire of n permits before and release after.
    */
  def withPermitN[A](n: Long)(rx: Rx[A]): Rx[A]

  /**
    * Get the current number of available permits.
    */
  def available: Rx[Long]

  /**
    * Get the current number of waiting acquires.
    */
  def waiting: Rx[Int]

end RxSemaphore

object RxSemaphore:

  /**
    * Create a new semaphore with the given number of permits.
    */
  def apply(permits: Long): RxSemaphore =
    require(permits >= 0, s"Permits must be non-negative: ${permits}")
    new RxSemaphoreImpl(permits)

  /**
    * Create a new semaphore with the given number of permits, wrapped in Rx.
    */
  def of(permits: Long): Rx[RxSemaphore] = Rx.single(apply(permits))

  /**
    * Waiter entry: number of permits requested and callback to invoke when satisfied. The callback
    * returns false if the waiting run was cancelled and the permits must be released again.
    */
  private class Waiter(val permits: Long, val callback: () => Boolean)

  /**
    * Internal state
    */
  private case class State(available: Long, waiters: Queue[Waiter])

  private class RxSemaphoreImpl(initialPermits: Long) extends RxSemaphore:
    private val state = new AtomicReference[State](State(initialPermits, Queue.empty))

    override def acquire: Rx[Unit] = acquireN(1)

    override def acquireN(n: Long): Rx[Unit] =
      require(n >= 0, s"Cannot acquire negative permits: ${n}")
      if n == 0 then
        Rx.single(())
      else
        Rx.AsyncOp[Unit](callback => acquireAsync(n, callback), s"RxSemaphore.acquireN(${n})")

    private def acquireAsync(n: Long, callback: Try[Unit] => Boolean): Cancelable =
      val waiter = Waiter(n, () => callback(Success(())))
      if acquireLoop(waiter) then
        deliver(waiter)
        Cancelable.empty
      else
        Cancelable(() => removeWaiter(waiter))

    /**
      * Hand the acquired permits to the waiter, or release them if its run was cancelled
      */
    private def deliver(waiter: Waiter): Unit =
      if !waiter.callback() then
        releaseLoop(waiter.permits)

    @tailrec
    private def acquireLoop(waiter: Waiter): Boolean =
      val current = state.get()
      if current.available >= waiter.permits then
        // Enough permits available
        val newState = current.copy(available = current.available - waiter.permits)
        if state.compareAndSet(current, newState) then
          true
        else
          acquireLoop(waiter)
      else
        // Need to wait
        val newState = current.copy(waiters = current.waiters.enqueue(waiter))
        if state.compareAndSet(current, newState) then
          false
        else
          acquireLoop(waiter)

    /**
      * Remove a cancelled waiter. Waiters queued behind it may now be satisfiable.
      */
    @tailrec
    private def removeWaiter(waiter: Waiter): Unit =
      val current = state.get()
      if current.waiters.exists(_ eq waiter) then
        val (toWake, remaining, leftover) = satisfyWaiters(
          current.waiters.filterNot(_ eq waiter),
          current.available
        )
        if state.compareAndSet(current, State(leftover, remaining)) then
          toWake.foreach(deliver)
        else
          removeWaiter(waiter)

    override def release: Rx[Unit] = releaseN(1)

    override def releaseN(n: Long): Rx[Unit] =
      require(n >= 0, s"Cannot release negative permits: ${n}")
      Rx.single {
        if n > 0 then
          releaseLoop(n)
      }

    @tailrec
    private def releaseLoop(n: Long): Unit =
      val current                       = state.get()
      val newAvailable                  = current.available + n
      val (toWake, remaining, leftover) = satisfyWaiters(current.waiters, newAvailable)
      val newState                      = State(leftover, remaining)
      if state.compareAndSet(current, newState) then
        // Wake up satisfied waiters
        toWake.foreach(deliver)
      else
        releaseLoop(n)

    /**
      * Try to satisfy waiters from the queue with available permits.
      * @return
      *   (waiters to wake, remaining waiters, leftover permits)
      */
    private def satisfyWaiters(
        waiters: Queue[Waiter],
        available: Long
    ): (List[Waiter], Queue[Waiter], Long) =
      var toWake    = List.empty[Waiter]
      var remaining = waiters
      var permits   = available

      while remaining.nonEmpty && remaining.head.permits <= permits do
        val (waiter, rest) = remaining.dequeue
        toWake = waiter :: toWake
        permits -= waiter.permits
        remaining = rest

      (toWake.reverse, remaining, permits)

    override def tryAcquire: Rx[Boolean] = tryAcquireN(1)

    override def tryAcquireN(n: Long): Rx[Boolean] =
      require(n >= 0, s"Cannot acquire negative permits: ${n}")
      Rx.single {
        tryAcquireLoop(n)
      }

    @tailrec
    private def tryAcquireLoop(n: Long): Boolean =
      val current = state.get()
      if current.available >= n then
        val newState = current.copy(available = current.available - n)
        if state.compareAndSet(current, newState) then
          true
        else
          tryAcquireLoop(n)
      else
        false

    override def withPermit[A](rx: Rx[A]): Rx[A] = withPermitN(1)(rx)

    override def withPermitN[A](n: Long)(rx: Rx[A]): Rx[A] = acquireN(n).flatMap(_ =>
      rx.transform { result =>
        // Release even on failure
        releaseLoop(n)
        result.get
      }
    )

    override def available: Rx[Long] = Rx.single(state.get().available)

    override def waiting: Rx[Int] = Rx.single(state.get().waiters.size)

  end RxSemaphoreImpl

end RxSemaphore
