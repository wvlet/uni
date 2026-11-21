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

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.scalajs.js
import scala.scalajs.js.timers.SetIntervalHandle
import scala.util.Try

/**
  */
object compat:
  def defaultExecutionContext: scala.concurrent.ExecutionContext =
    MacrotaskExecutor.Implicits.global

  def scheduleOnce[U](delayMills: Long)(body: => U): Cancelable =
    val timeoutHandle = scala.scalajs.js.timers.setTimeout(delayMills.toDouble)(body)
    Cancelable { () =>
      Option(timeoutHandle).foreach { handle =>
        Try(scala.scalajs.js.timers.clearTimeout(handle))
      }
    }

  /**
    * Run `body` after `initialDelayMillis`, then every `periodMillis`.
    */
  def schedulePeriodically(initialDelayMillis: Long, periodMillis: Long)(
      body: => Unit
  ): Cancelable =
    var cancelled                                 = false
    var intervalHandle: Option[SetIntervalHandle] = None
    val first                                     =
      js.timers
        .setTimeout(initialDelayMillis.max(0L).toDouble) {
          if !cancelled then
            intervalHandle = Some(js.timers.setInterval(periodMillis.max(1L).toDouble)(body))
            body
        }
    Cancelable { () =>
      cancelled = true
      Try(js.timers.clearTimeout(first))
      intervalHandle.foreach(h => Try(js.timers.clearInterval(h)))
    }

  /**
    * requestAnimationFrame in a browser, a 16 ms timer elsewhere (e.g., Node.js).
    */
  lazy val defaultFrameProvider: RxFrameProvider =
    if js.typeOf(js.Dynamic.global.requestAnimationFrame) == "function" then
      AnimationFrameProvider
    else
      RxFrameProvider.fromTime(16L)(using RxTimeProvider.system)

  private object AnimationFrameProvider extends RxFrameProvider:
    override def toString: String                        = "RxFrameProvider(requestAnimationFrame)"
    override def requestFrame(body: => Unit): Cancelable =
      val id = js.Dynamic.global.requestAnimationFrame((_: Double) => body)
      Cancelable { () =>
        js.Dynamic.global.cancelAnimationFrame(id)
      }

  def toSeq[A](rx: Rx[A]): Seq[A] =
    throw new UnsupportedOperationException("Rx.toSeq is unsupported in Scala.js")

  private[rx] def await[A](rx: RxOps[A]): A =
    throw new UnsupportedOperationException("Rx.await is unsupported in Scala.js")

end compat
