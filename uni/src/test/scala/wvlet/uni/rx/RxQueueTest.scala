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

import wvlet.uni.test.*

import java.util.concurrent.TimeUnit
import scala.collection.mutable

/**
  * Sources fed by an external process (Rx.queue, RxBoundedQueue), subscribed before any event is
  * added. Tests return Rx, so they also run on Scala.js.
  */
class RxQueueTest extends UniTest:

  // Run `body` after the test has subscribed to the Rx it returns
  private def later(body: => Unit): Unit = Rx.delay(30, TimeUnit.MILLISECONDS).run(_ => body)

  private def collect[A](rx: Rx[A]): Rx[Seq[A]] =
    val values = mutable.ArrayBuffer.empty[A]
    rx.tap(v => values.synchronized(values += v))
      .lastOption
      .toRx
      .map(_ => values.synchronized(values.toSeq))

  test("a subscriber of an empty queue receives values put later") {
    val queue = Rx.queue[Int]()
    later {
      queue.put(1)
      queue.put(2)
      queue.put(3)
    }
    collect(queue.take(3)).map(_ shouldBe Seq(1, 2, 3))
  }

  test("stop completes the stream after the queued values") {
    val queue = Rx.queue[String]()
    later {
      queue.put("a")
      queue.stop()
    }
    collect(queue).map(_ shouldBe Seq("a"))
  }

  test("subscribing to an empty queue returns without waiting for an event") {
    val queue  = Rx.queue[Int]()
    val values = mutable.ArrayBuffer.empty[Int]
    val c      = queue.subscribe(v => values += v)
    c.cancel
    values shouldBe empty
  }

  test("cancelling a waiting subscriber leaves the queue usable") {
    val queue = Rx.queue[Int]()
    queue.subscribe(_ => ()).cancel
    // The cancelled subscriber neither consumes these nor leaves an error in the queue
    queue.put(1)
    queue.put(2)
    collect(queue.take(2)).map(_ shouldBe Seq(1, 2))
  }

  test("a subscriber of an empty RxBoundedQueue receives values offered later") {
    val queue = RxBoundedQueue[Int](4)
    later {
      queue.put(1)
      queue.put(2)
    }
    collect(queue.take(2)).map(_ shouldBe Seq(1, 2))
  }

  test("cancelling a waiting RxBoundedQueue subscriber does not complete the queue") {
    val queue = RxBoundedQueue[Int](4)
    queue.subscribe(_ => ()).cancel
    queue.put(1)
    collect(queue.take(1)).map(_ shouldBe Seq(1))
  }

  test("stop completes a waiting RxBoundedQueue subscriber") {
    val queue = RxBoundedQueue[Int](4)
    later {
      queue.put(1)
      queue.stop()
    }
    collect(queue).map(_ shouldBe Seq(1))
  }

  test("other subscribers keep receiving after one cancels") {
    val queue = Rx.queue[Int]()
    val first = queue.subscribe(_ => ())
    val rest  = collect(queue.take(2))
    first.cancel
    later {
      queue.put(1)
      queue.put(2)
    }
    rest.map(_ shouldBe Seq(1, 2))
  }

  test("continuous mode keeps reading after an error") {
    val queue  = Rx.queue[Int]()
    val events = mutable.ArrayBuffer.empty[RxEvent]
    val c      =
      RxRunner.runContinuously(
        queue.map(v =>
          if v == 1 then
            throw IllegalStateException("once")
          else
            v
        )
      )(ev => events += ev)
    queue.put(1)
    queue.put(2)
    c.cancel
    events.collect { case OnNext(v) =>
      v
    } shouldBe Seq(2)
    events.count(_.isInstanceOf[OnError]) shouldBe 1
  }

end RxQueueTest
