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

import java.util.concurrent.TimeUnit
import scala.collection.mutable

/**
  * The clock and scheduler behind Rx time operators (`Rx.interval`, `Rx.timer`, `throttleFirst`,
  * `throttleLast`, `timeout`).
  *
  * Each time operator takes a `using RxTimeProvider` parameter that defaults to
  * [[RxTimeProvider.system]]. Put a [[ManualTimeProvider]] in scope to drive the time by hand:
  *
  * {{{
  *   given time: ManualTimeProvider = RxTimeProvider.manual()
  *   val ticks = Seq.newBuilder[Long]
  *   Rx.interval(100, TimeUnit.MILLISECONDS).take(3).run(ticks += _)
  *   time.advance(300, TimeUnit.MILLISECONDS) // runs the three ticks on this thread
  * }}}
  *
  * The provider is captured when the operator is built, so code that builds its own `Rx.interval`
  * outside the scope of the given keeps the system clock.
  */
trait RxTimeProvider extends Ticker:

  /**
    * The current time in nanoseconds. Only differences between two readings are meaningful.
    */
  def currentNanos: Long

  /**
    * Run `body` once after `delayMillis` milliseconds.
    */
  def scheduleOnce(delayMillis: Long)(body: => Unit): Cancelable

  /**
    * Run `body` after `initialDelayMillis`, then every `periodMillis` after the previous run ends.
    */
  def schedulePeriodically(initialDelayMillis: Long, periodMillis: Long)(body: => Unit): Cancelable

end RxTimeProvider

object RxTimeProvider:

  /**
    * The platform clock: `System.nanoTime` and a shared timer thread on the JVM and Scala Native,
    * `setTimeout` / `setInterval` on Scala.js.
    */
  val system: RxTimeProvider =
    new RxTimeProvider:
      override def toString: String   = "RxTimeProvider.system"
      override def currentNanos: Long = System.nanoTime()
      override def scheduleOnce(delayMillis: Long)(body: => Unit): Cancelable =
        compat.scheduleOnce(delayMillis)(body)

      override def schedulePeriodically(initialDelayMillis: Long, periodMillis: Long)(
          body: => Unit
      ): Cancelable = compat.schedulePeriodically(initialDelayMillis, periodMillis)(body)

  /**
    * The default provider for time operators when no other `given RxTimeProvider` is in scope.
    */
  given default: RxTimeProvider = system

  /**
    * A provider whose time moves only when [[ManualTimeProvider.advance]] is called.
    */
  def manual(startNanos: Long = 0L): ManualTimeProvider = ManualTimeProvider(startNanos)

end RxTimeProvider

/**
  * An [[RxTimeProvider]] for tests. Time stands still until [[advance]] is called, which runs every
  * task that falls due, in time order, on the calling thread. A task scheduled while advancing runs
  * in the same call when it falls due before the target time.
  */
class ManualTimeProvider(startNanos: Long = 0L) extends RxTimeProvider:
  private class Task(var dueNanos: Long, val periodNanos: Long, val seq: Long, body: () => Unit)
      extends Cancelable:
    @volatile
    var cancelled = false

    def run(): Unit           = body()
    override def cancel: Unit =
      cancelled = true
      ManualTimeProvider.this.synchronized {
        tasks -= this
      }

  private given Ordering[Task] = Ordering.by[Task, (Long, Long)](t => (t.dueNanos, t.seq))

  private var now: Long                    = startNanos
  private var nextSeq: Long                = 0L
  private val tasks: mutable.TreeSet[Task] = mutable.TreeSet.empty[Task]

  override def toString: String = s"ManualTimeProvider(${currentNanos}ns)"

  override def currentNanos: Long = synchronized(now)

  /**
    * The number of scheduled tasks that have not run (or, for periodic tasks, are still active).
    */
  def pendingTasks: Int = synchronized(tasks.size)

  override def scheduleOnce(delayMillis: Long)(body: => Unit): Cancelable = addTask(
    TimeUnit.MILLISECONDS.toNanos(delayMillis.max(0L)),
    0L,
    () => body
  )

  override def schedulePeriodically(initialDelayMillis: Long, periodMillis: Long)(
      body: => Unit
  ): Cancelable = addTask(
    TimeUnit.MILLISECONDS.toNanos(initialDelayMillis.max(0L)),
    TimeUnit.MILLISECONDS.toNanos(periodMillis.max(1L)),
    () => body
  )

  private def addTask(delayNanos: Long, periodNanos: Long, body: () => Unit): Task = synchronized {
    val t = Task(now + delayNanos, periodNanos, nextSeq, body)
    nextSeq += 1
    tasks += t
    t
  }

  /**
    * Move the time forward and run the tasks that fall due on the way.
    */
  def advance(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): ManualTimeProvider =
    val target = synchronized(now) + unit.toNanos(time)
    var done   = false
    while !done do
      val next: Option[Task] = synchronized {
        tasks.headOption.filter(_.dueNanos <= target) match
          case Some(t) =>
            tasks -= t
            now = t.dueNanos
            if t.periodNanos > 0 then
              t.dueNanos += t.periodNanos
              tasks += t
            Some(t)
          case None =>
            now = target
            None
      }
      next match
        case Some(t) if !t.cancelled =>
          t.run()
        case Some(_) =>
        // cancelled while it was due
        case None =>
          done = true
    this

  /**
    * Run the tasks that are due now without moving the time.
    */
  def runDueTasks(): ManualTimeProvider = advance(0L)

end ManualTimeProvider
