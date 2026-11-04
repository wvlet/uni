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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js

/**
  * Reactive online/offline status tracking.
  *
  * Usage:
  * {{{
  *   div(
  *     NetworkStatus.online.map { online =>
  *       if online then span(cls := "status-online", "Connected")
  *       else span(cls := "status-offline", "Offline - Changes will sync when reconnected")
  *     }
  *   )
  * }}}
  */
object NetworkStatus:

  private class OnlineStatusVar extends Cancelable:
    private val underlying = Rx.variable(dom.window.navigator.onLine)

    private val onlineHandler: js.Function1[dom.Event, Unit]  = _ => underlying := true
    private val offlineHandler: js.Function1[dom.Event, Unit] = _ => underlying := false

    dom.window.addEventListener("online", onlineHandler)
    dom.window.addEventListener("offline", offlineHandler)

    def get: Boolean                   = underlying.get
    def rx: Rx[Boolean]                = underlying
    def map[B](f: Boolean => B): Rx[B] = underlying.map(f)

    override def cancel: Unit =
      dom.window.removeEventListener("online", onlineHandler)
      dom.window.removeEventListener("offline", offlineHandler)

  end OnlineStatusVar

  private lazy val instance: OnlineStatusVar = OnlineStatusVar()

  /**
    * Get the current online status.
    */
  def isOnline: Boolean = instance.get

  /**
    * Reactive stream of online status. Emits true when online, false when offline.
    */
  def online: Rx[Boolean] = instance.rx

  /**
    * Reactive stream of offline status. Emits true when offline, false when online.
    */
  def offline: Rx[Boolean] = instance.map(!_)

  /**
    * Stop listening to network status changes. Call this when the application is shutting down to
    * clean up event listeners.
    */
  def stop(): Unit = instance.cancel

end NetworkStatus
