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
import wvlet.uni.rx.{Cancelable, Rx}

import scala.scalajs.js

/**
  * Reactive document visibility state tracking.
  *
  * Tracks whether the page is visible or hidden (e.g., when switching tabs or minimizing the
  * browser).
  *
  * Usage:
  * {{{
  *   // Pause video when tab is hidden
  *   WindowVisibility.visible.run { visible =>
  *     if visible then video.play() else video.pause()
  *   }
  *
  *   // Show indicator when page is hidden
  *   div(
  *     WindowVisibility.hidden.map { hidden =>
  *       if hidden then span("Page is hidden")
  *       else DomNode.empty
  *     }
  *   )
  * }}}
  */
object WindowVisibility:

  private class VisibilityVar extends Cancelable:
    private val underlying = Rx.variable(dom.document.visibilityState)

    private val handler: js.Function1[dom.Event, Unit] =
      _ => underlying := dom.document.visibilityState

    dom.document.addEventListener("visibilitychange", handler)

    def get: String        = underlying.get
    def rx: Rx[String]     = underlying
    def isVisible: Boolean = get == "visible"

    override def cancel: Unit = dom.document.removeEventListener("visibilitychange", handler)

  end VisibilityVar

  private lazy val instance: VisibilityVar = VisibilityVar()

  /**
    * Get the current visibility state as a boolean.
    */
  def isVisible: Boolean = instance.isVisible

  /**
    * Get the current visibility state string ("visible", "hidden", etc.).
    */
  def state: String = instance.get

  /**
    * Reactive stream of visibility as boolean. Emits true when visible, false otherwise.
    */
  def visible: Rx[Boolean] = instance.rx.map(_ == "visible")

  /**
    * Reactive stream of hidden state. Emits true when hidden, false when visible.
    */
  def hidden: Rx[Boolean] = instance.rx.map(_ != "visible")

  /**
    * Reactive stream of visibility state string.
    */
  def visibilityState: Rx[String] = instance.rx

  /**
    * Stop listening to visibility changes. Call this when the application is shutting down to clean
    * up event listeners.
    */
  def stop(): Unit = instance.cancel

end WindowVisibility
