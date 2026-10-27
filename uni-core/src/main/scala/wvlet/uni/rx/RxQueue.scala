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

import wvlet.uni.log.LogSupport

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.util.Success
import scala.util.Try

/**
  * JVM/JS compatible RxQueue implementation
  * @tparam A
  */
class RxQueue[A]() extends RxSource[A] with LogSupport:
  override def parents: Seq[Rx[?]] = Seq.empty

  private var queue = Queue.empty[RxEvent]
  // Callbacks of subscribers waiting for an event. A callback returns false if its subscription
  // was cancelled, and the event goes back to the queue
  private var waiters = Queue.empty[Try[RxEvent] => Boolean]

  override def add(event: RxEvent): Unit = offer(event, front = false)

  @tailrec
  private def offer(event: RxEvent, front: Boolean): Unit =
    val waiter = synchronized {
      if waiters.nonEmpty then
        val (w, rest) = waiters.dequeue
        waiters = rest
        Some(w)
      else
        queue =
          if front then
            event +: queue
          else
            queue.enqueue(event)
        None
    }
    waiter match
      case Some(w) if !w(Success(event)) =>
        offer(event, front = true)
      case _ =>

  override def next: Rx[RxEvent] = Rx.AsyncOp[RxEvent](
    callback =>
      val ready = synchronized {
        if queue.nonEmpty then
          val (e, rest) = queue.dequeue
          queue = rest
          Some(e)
        else
          waiters = waiters.enqueue(callback)
          None
      }
      ready match
        case Some(e) =>
          if !callback(Success(e)) then
            offer(e, front = true)
          Cancelable.empty
        case None =>
          Cancelable { () =>
            synchronized {
              waiters = waiters.filterNot(_ eq callback)
            }
          }
    ,
    "RxQueue.next"
  )

  // Cancelling the subscription to next unregisters its waiter
  override def cancelNext(): Unit = ()

end RxQueue
