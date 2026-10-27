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
  * A bounded queue for backpressure support.
  *
  * RxBoundedQueue provides a bounded buffer between producers and consumers. When the queue is
  * full, producers are semantically blocked until space becomes available. When empty, consumers
  * are semantically blocked until elements are available.
  *
  * @tparam A
  *   the element type
  */
trait RxBoundedQueue[A] extends RxSource[A]:

  /**
    * Try to offer an element without blocking. Returns false if the queue is full.
    */
  def tryOffer(a: A): Rx[Boolean]

  /**
    * Offer an element, semantically blocking until space is available.
    */
  def offer(a: A): Rx[Unit]

  /**
    * Try to take an element without blocking. Returns None if the queue is empty.
    */
  def tryTake: Rx[Option[A]]

  /**
    * Take an element, semantically blocking until one is available.
    */
  def take: Rx[A]

  /**
    * Get the current number of elements in the queue.
    */
  def size: Rx[Int]

  /**
    * Check if the queue is empty.
    */
  def isEmpty: Rx[Boolean]

  /**
    * Check if the queue is full.
    */
  def isFull: Rx[Boolean]

  /**
    * The maximum capacity of the queue.
    */
  def capacity: Int

end RxBoundedQueue

object RxBoundedQueue:

  /**
    * Create a new bounded queue with the given capacity.
    */
  def apply[A](capacity: Int): RxBoundedQueue[A] =
    require(capacity > 0, s"Capacity must be positive: ${capacity}")
    new RxBoundedQueueImpl[A](capacity)

  /**
    * Create a new bounded queue wrapped in Rx.
    */
  def of[A](capacity: Int): Rx[RxBoundedQueue[A]] = Rx.single(apply(capacity))

  /**
    * Internal state
    */
  private case class State[A](
      elements: Queue[A],
      // A taker returns false if its run was cancelled and the element must be returned
      takers: Queue[A => Boolean],
      offerers: Queue[(A, () => Unit)]
  )

  private class RxBoundedQueueImpl[A](val capacity: Int) extends RxBoundedQueue[A]:
    private val state = new AtomicReference[State[A]](State(Queue.empty, Queue.empty, Queue.empty))
    private val completed = new AtomicReference[Option[Throwable]](None)

    // RxSource implementation
    override def parents: Seq[RxOps[?]] = Seq.empty

    override def add(ev: RxEvent): Unit =
      ev match
        case OnNext(v) =>
          // Use tryOffer for non-blocking behavior; if queue is full, the element is dropped
          // For blocking behavior, use offer() directly
          tryOfferLoop(v.asInstanceOf[A])
          ()
        case OnError(e) =>
          completed.set(Some(e))
        case OnCompletion =>
          completed.set(Some(null)) // null means normal completion

    override def next: Rx[RxEvent] = take.map(OnNext(_))

    // Cancelling the take already unregisters the waiter; adding an error would complete the queue
    override def cancelNext(): Unit = ()

    override def tryOffer(a: A): Rx[Boolean] = Rx.single {
      tryOfferLoop(a)
    }

    @tailrec
    private def tryOfferLoop(a: A): Boolean =
      val current = state.get()
      if current.takers.nonEmpty then
        // There's a waiting taker - give directly
        val (taker, remainingTakers) = current.takers.dequeue
        val newState                 = current.copy(takers = remainingTakers)
        if state.compareAndSet(current, newState) then
          giveTo(taker, a)
          true
        else
          tryOfferLoop(a)
      else if current.elements.size < capacity then
        // Queue has space
        val newState = current.copy(elements = current.elements.enqueue(a))
        if state.compareAndSet(current, newState) then
          true
        else
          tryOfferLoop(a)
      else
        // Queue is full
        false

    override def offer(a: A): Rx[Unit] =
      if tryOfferLoop(a) then
        Rx.single(())
      else
        Rx.AsyncOp[Unit](callback => offerAsync(a, callback), "RxBoundedQueue.offer")

    private def offerAsync(a: A, callback: Try[Unit] => Boolean): Cancelable =
      // Once the element is accepted, the offer stands even if its run was cancelled meanwhile
      val offerer = (a, () => callback(Success(())): Unit)
      if offerWithCallback(offerer) then
        offerer._2()
        Cancelable.empty
      else
        Cancelable(() => removeOfferer(offerer))

    @tailrec
    private def offerWithCallback(offerer: (A, () => Unit)): Boolean =
      val a       = offerer._1
      val current = state.get()
      if current.takers.nonEmpty then
        // Give to waiting taker
        val (taker, remainingTakers) = current.takers.dequeue
        val newState                 = current.copy(takers = remainingTakers)
        if state.compareAndSet(current, newState) then
          giveTo(taker, a)
          true // Completed immediately
        else
          offerWithCallback(offerer)
      else if current.elements.size < capacity then
        // Queue has space
        val newState = current.copy(elements = current.elements.enqueue(a))
        if state.compareAndSet(current, newState) then
          true // Completed immediately
        else
          offerWithCallback(offerer)
      else
        // Queue full - register as waiting offerer
        val newState = current.copy(offerers = current.offerers.enqueue(offerer))
        if state.compareAndSet(current, newState) then
          false // Will complete later
        else
          offerWithCallback(offerer)

    /**
      * Remove a cancelled offerer whose element has not been accepted yet
      */
    @tailrec
    private def removeOfferer(offerer: (A, () => Unit)): Unit =
      val current = state.get()
      if current.offerers.exists(_ eq offerer) then
        val newState = current.copy(offerers = current.offerers.filterNot(_ eq offerer))
        if !state.compareAndSet(current, newState) then
          removeOfferer(offerer)

    /**
      * Hand an element to a waiting taker, or put it back if the taker's run was cancelled
      */
    @tailrec
    private def giveTo(taker: A => Boolean, a: A): Unit =
      if !taker(a) then
        returnLoop(a) match
          case Some(next) =>
            giveTo(next, a)
          case None =>
            ()

    /**
      * Return an element to the next waiting taker, or else to the front of the queue. The queue
      * may exceed its capacity until returned elements are taken; waiting offerers stay queued
      * meanwhile.
      */
    @tailrec
    private def returnLoop(a: A): Option[A => Boolean] =
      val current = state.get()
      if current.takers.nonEmpty then
        val (taker, remainingTakers) = current.takers.dequeue
        if state.compareAndSet(current, current.copy(takers = remainingTakers)) then
          Some(taker)
        else
          returnLoop(a)
      else if state.compareAndSet(current, current.copy(elements = a +: current.elements)) then
        None
      else
        returnLoop(a)

    override def tryTake: Rx[Option[A]] = Rx.single {
      tryTakeLoop()
    }

    @tailrec
    private def tryTakeLoop(): Option[A] =
      val current = state.get()
      if current.elements.nonEmpty then
        val (element, remainingElements) = current.elements.dequeue

        // Check if there are waiting offerers
        // Admit a waiting offerer only below capacity (returned elements may exceed it)
        if current.offerers.nonEmpty && remainingElements.size < capacity then
          val ((offerElement, offerCallback), remainingOfferers) = current.offerers.dequeue
          val newState                                           = current.copy(
            elements = remainingElements.enqueue(offerElement),
            offerers = remainingOfferers
          )
          if state.compareAndSet(current, newState) then
            offerCallback() // Signal offerer that their element was accepted
            Some(element)
          else
            tryTakeLoop()
        else
          val newState = current.copy(elements = remainingElements)
          if state.compareAndSet(current, newState) then
            Some(element)
          else
            tryTakeLoop()
      else
        None

    override def take: Rx[A] =
      tryTakeLoop() match
        case Some(a) =>
          Rx.single(a)
        case None =>
          completed.get() match
            case Some(null) =>
              Rx.exception(new NoSuchElementException("Queue completed"))
            case Some(e) =>
              Rx.exception(e)
            case None =>
              Rx.AsyncOp[A](takeAsync, "RxBoundedQueue.take")

    private def takeAsync(callback: Try[A] => Boolean): Cancelable =
      val taker = (a: A) => callback(Success(a))
      takeWithCallback(taker) match
        case Some(a) =>
          giveTo(taker, a)
          Cancelable.empty
        case None =>
          Cancelable(() => removeTaker(taker))

    /**
      * Remove a cancelled taker that has not received an element yet
      */
    @tailrec
    private def removeTaker(taker: A => Boolean): Unit =
      val current = state.get()
      if current.takers.exists(_ eq taker) then
        val newState = current.copy(takers = current.takers.filterNot(_ eq taker))
        if !state.compareAndSet(current, newState) then
          removeTaker(taker)

    @tailrec
    private def takeWithCallback(callback: A => Boolean): Option[A] =
      val current = state.get()
      if current.elements.nonEmpty then
        val (element, remainingElements) = current.elements.dequeue

        // Check if there are waiting offerers
        // Admit a waiting offerer only below capacity (returned elements may exceed it)
        if current.offerers.nonEmpty && remainingElements.size < capacity then
          val ((offerElement, offerCallback), remainingOfferers) = current.offerers.dequeue
          val newState                                           = current.copy(
            elements = remainingElements.enqueue(offerElement),
            offerers = remainingOfferers
          )
          if state.compareAndSet(current, newState) then
            offerCallback()
            Some(element)
          else
            takeWithCallback(callback)
        else
          val newState = current.copy(elements = remainingElements)
          if state.compareAndSet(current, newState) then
            Some(element)
          else
            takeWithCallback(callback)
      else
        // Queue empty - register as waiting taker
        val newState = current.copy(takers = current.takers.enqueue(callback))
        if state.compareAndSet(current, newState) then
          None // Will complete later
        else
          takeWithCallback(callback)

    end takeWithCallback

    override def size: Rx[Int] = Rx.single(state.get().elements.size)

    override def isEmpty: Rx[Boolean] = Rx.single(state.get().elements.isEmpty)

    override def isFull: Rx[Boolean] = Rx.single(state.get().elements.size >= capacity)

  end RxBoundedQueueImpl

end RxBoundedQueue
