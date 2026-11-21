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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

/**
  * A subscription that an [[RxTracker]] saw start and that has not ended yet.
  *
  * @param id
  *   a number unique within the process
  * @param rx
  *   the Rx that was run
  * @param mode
  *   how it was run: `run`, `runOnce`, or `runContinuously` (`subscribe`)
  * @param stackTrace
  *   where the subscription started, or empty when the tracker does not capture stack traces
  */
case class RxSubscription(id: Long, rx: RxOps[?], mode: String, stackTrace: Seq[StackTraceElement]):
  /**
    * The first stack frame below the Rx entry points (`run`, `subscribe`, `RxRunner`, ...): the
    * code that started the subscription.
    */
  def origin: Option[StackTraceElement] =
    stackTrace.dropWhile(e => RxSubscription.isEntryFrame(e.getClassName)).headOption

  override def toString: String =
    val at = origin.map(e => s" at ${e}").getOrElse("")
    s"RxSubscription(#${id} ${mode} ${rx.getClass.getSimpleName}${at})"

end RxSubscription

object RxSubscription:
  private val entryFrame =
    """(java\.lang\.Throwable|java\.lang\.Exception|wvlet\.uni\.rx\.(RxTracker|RxRunner|RxOps|Rx|RxVar|RxVarOps|RxOption|RxOptionOps|RxStream|compat))([$.].*)?"""
      .r

  private[rx] def isEntryFrame(className: String): Boolean = entryFrame.matches(className)

/**
  * Records the Rx subscriptions that are still active, to find subscriptions nobody cancels.
  *
  * A subscription starts when an Rx is run (`run`, `subscribe`, `RxRunner.runContinuously`, ...).
  * It ends when it is cancelled or, outside continuous mode, when the stream reports `OnError` or
  * `OnCompletion`. A `subscribe` (continuous) subscription stays active until it is cancelled,
  * which makes it the usual leak: an `RxVar` bound to a DOM node that was removed.
  *
  * Track the subscriptions that a block of code starts on the current thread:
  * {{{
  *   val tracker = RxTracker()
  *   tracker.trace {
  *     counter.subscribe(render)
  *   }
  *   tracker.activeSubscriptions // Seq(RxSubscription(#1 runContinuously RxVar at ...))
  * }}}
  *
  * Or track every subscription in the process, e.g. for a debug view:
  * {{{
  *   val tracker = RxTracker.startGlobal()
  *   ...
  *   tracker.activeSubscriptions.foreach(s => warn(s))
  *   RxTracker.stopGlobal()
  * }}}
  *
  * Tracking costs nothing while no tracker is active. Capturing stack traces is the main cost of an
  * active tracker; pass `captureStackTrace = false` to skip it.
  */
class RxTracker(val captureStackTrace: Boolean = true):
  private val active = mutable.LinkedHashMap.empty[Long, RxSubscription]

  override def toString: String = s"RxTracker(active: ${activeCount})"

  /**
    * The active subscriptions, oldest first.
    */
  def activeSubscriptions: Seq[RxSubscription] = synchronized(active.values.toList)

  def activeCount: Int = synchronized(active.size)

  /**
    * Forget the subscriptions recorded so far.
    */
  def clear(): Unit = synchronized(active.clear())

  /**
    * Track the subscriptions that `body` starts on the current thread. Subscriptions started from
    * another thread, for example in a Future callback, are not tracked. Nested `trace` blocks of
    * other trackers record the inner subscriptions in every enclosing tracker.
    */
  def trace[A](body: => A): A =
    val outer = RxTracker.localTrackers.get()
    RxTracker.localTrackers.set(this :: outer)
    RxTracker.enterScope()
    try
      body
    finally
      RxTracker.exitScope()
      RxTracker.localTrackers.set(outer)

  private[rx] def add(s: RxSubscription): Unit = synchronized(active.put(s.id, s))

  private[rx] def remove(id: Long): Unit = synchronized(active.remove(id))

end RxTracker

object RxTracker:
  private val nextId = AtomicLong(0L)

  // The number of running trace blocks on all threads, so that onSubscribe can skip the
  // thread-local lookup when nothing is traced
  private val scopes = java.util.concurrent.atomic.AtomicInteger(0)

  @volatile
  private var globalTracker: RxTracker = null

  private val localTrackers: ThreadLocal[List[RxTracker]] =
    new ThreadLocal[List[RxTracker]]:
      override def initialValue(): List[RxTracker] = Nil

  private def enterScope(): Unit = scopes.incrementAndGet()
  private def exitScope(): Unit  = scopes.decrementAndGet()

  /**
    * Track every subscription started from now on, on any thread, until [[stopGlobal]]. Calling it
    * again returns the running global tracker.
    */
  def startGlobal(captureStackTrace: Boolean = true): RxTracker = synchronized {
    if globalTracker == null then
      globalTracker = RxTracker(captureStackTrace)
    globalTracker
  }

  /**
    * Stop the global tracker. Its recorded subscriptions stay readable.
    */
  def stopGlobal(): Unit = synchronized {
    globalTracker = null
  }

  /**
    * The running global tracker, if any.
    */
  def global: Option[RxTracker] = Option(globalTracker)

  /**
    * Ends a tracked subscription. `release` is idempotent.
    */
  private[rx] final class Handle(trackers: List[RxTracker], id: Long):
    private val released = AtomicBoolean(false)
    def release(): Unit  =
      if released.compareAndSet(false, true) then
        trackers.foreach(_.remove(id))

  /**
    * Record a subscription that is starting, or return null when no tracker is active.
    */
  private[rx] def onSubscribe(rx: RxOps[?], mode: String): Handle =
    val g     = globalTracker
    val local =
      if scopes.get() == 0 then
        Nil
      else
        localTrackers.get()
    val trackers =
      if g == null then
        local
      else
        (g :: local).distinct
    if trackers.isEmpty then
      null
    else
      val id    = nextId.incrementAndGet()
      val trace =
        if trackers.exists(_.captureStackTrace) then
          Exception().getStackTrace.toSeq
        else
          Seq.empty
      trackers.foreach { t =>
        val st =
          if t.captureStackTrace then
            trace
          else
            Seq.empty
        t.add(RxSubscription(id, rx, mode, st))
      }
      Handle(trackers, id)

  end onSubscribe

end RxTracker
