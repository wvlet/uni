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
  * Reactive scroll position tracking with built-in throttling.
  *
  * Uses 16ms throttle (~60fps) with leading + trailing edge updates for smooth performance while
  * ensuring the final scroll position is always captured.
  *
  * Usage:
  * {{{
  *   // Show "back to top" button when scrolled
  *   div(
  *     WindowScroll.scrollY.map { y =>
  *       if y > 500 then button("Back to Top")
  *       else DomNode.empty
  *     }
  *   )
  *
  *   // Track scroll position
  *   WindowScroll.scroll.run { case (x, y) =>
  *     println(s"Scrolled to: \${x}, \${y}")
  *   }
  * }}}
  */
object WindowScroll:

  private val throttleMs = 16 // ~60fps

  private class ScrollVar extends Cancelable:
    private val scrollXVar = Rx.variable(dom.window.scrollX)
    private val scrollYVar = Rx.variable(dom.window.scrollY)

    private var lastUpdateTime: Double           = 0
    private var scheduledUpdate: js.UndefOr[Int] = js.undefined

    private def updateValues(): Unit =
      scrollXVar := dom.window.scrollX
      scrollYVar := dom.window.scrollY

    private val handler: js.Function1[dom.Event, Unit] =
      _ =>
        val now = js.Date.now()
        if now - lastUpdateTime >= throttleMs then
          // Leading edge: update immediately
          lastUpdateTime = now
          updateValues()
        else if scheduledUpdate.isEmpty then
          // Trailing edge: schedule final update
          val delay = throttleMs - (now - lastUpdateTime).toInt
          scheduledUpdate = dom
            .window
            .setTimeout(
              () =>
                scheduledUpdate = js.undefined
                lastUpdateTime = js.Date.now()
                updateValues()
              ,
              delay
            )

    dom.window.addEventListener("scroll", handler)

    def getX: Double    = scrollXVar.get
    def getY: Double    = scrollYVar.get
    def rxX: Rx[Double] = scrollXVar
    def rxY: Rx[Double] = scrollYVar

    override def cancel: Unit =
      dom.window.removeEventListener("scroll", handler)
      scheduledUpdate.foreach(dom.window.clearTimeout)
      scheduledUpdate = js.undefined

  end ScrollVar

  private lazy val instance: ScrollVar = ScrollVar()

  /**
    * Get current horizontal scroll position.
    */
  def x: Double = instance.getX

  /**
    * Get current vertical scroll position.
    */
  def y: Double = instance.getY

  /**
    * Reactive stream of horizontal scroll position.
    */
  def scrollX: Rx[Double] = instance.rxX

  /**
    * Reactive stream of vertical scroll position.
    */
  def scrollY: Rx[Double] = instance.rxY

  /**
    * Reactive stream of combined scroll position (x, y). Emits when either x or y changes.
    */
  def scroll: Rx[(Double, Double)] = instance.rxX.join(instance.rxY)

  /**
    * Stop listening to scroll events. Call this when the application is shutting down to clean up
    * event listeners.
    */
  def stop(): Unit = instance.cancel

end WindowScroll
