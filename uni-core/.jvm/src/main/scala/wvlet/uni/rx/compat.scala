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

import wvlet.uni.util.Result

import wvlet.uni.util.ThreadUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.concurrent.Promise

/**
  */
object compat:
  def defaultExecutionContext: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  private lazy val scheduledThreadManager = Executors.newScheduledThreadPool(
    1,
    ThreadUtil.newDaemonThreadFactory("rx-timer")
  )

  def scheduleOnce[U](delayMills: Long)(body: => U): Cancelable =
    val schedule = scheduledThreadManager.schedule(
      new Runnable:
        override def run(): Unit = body
      ,
      delayMills,
      TimeUnit.MILLISECONDS
    )
    Cancelable { () =>
      schedule.cancel(false)
    }

  /**
    * Run `body` after `initialDelayMillis`, then every `periodMillis` after the previous run ends.
    */
  def schedulePeriodically(initialDelayMillis: Long, periodMillis: Long)(
      body: => Unit
  ): Cancelable =
    val schedule = scheduledThreadManager.scheduleWithFixedDelay(
      new Runnable:
        override def run(): Unit = body
      ,
      initialDelayMillis.max(0L),
      periodMillis.max(1L),
      TimeUnit.MILLISECONDS
    )
    Cancelable { () =>
      schedule.cancel(false)
    }

  lazy val defaultFrameProvider: RxFrameProvider =
    RxFrameProvider.fromTime(16L)(using RxTimeProvider.system)

  def toSeq[A](rx: Rx[A]): Seq[A] =
    val ready = new AtomicBoolean(true)
    val s     = Seq.newBuilder[A]
    var c     = Cancelable.empty
    c =
      RxRunner.run(rx) {
        case OnNext(v) =>
          s += v.asInstanceOf[A]
        case OnCompletion(Result.Failure(e)) =>
          c.cancel
          throw e
        case OnCompletion(Result.Success(_)) =>
          c.cancel
          ready.set(true)
      }
    while !ready.get() do {}
    s.result()

  private[rx] def await[A](rx: RxOps[A]): A =
    val p = Promise[A]()
    val c =
      RxRunner.runOnce(rx) {
        case OnNext(v) =>
          p.trySuccess(v.asInstanceOf[A])
        case OnCompletion(Result.Failure(e)) =>
          p.tryFailure(e)
        case OnCompletion(Result.Success(_)) =>
          // If promise not yet completed, complete with Unit (for Rx[Unit] tests)
          p.trySuccess(().asInstanceOf[A])
      }
    try Await.result(p.future, Duration.Inf)
    finally c.cancel

end compat
