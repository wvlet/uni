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
  * Reactive window dimensions tracking with built-in throttling.
  *
  * Tracks viewport and window sizes, updating on resize events. Uses 100ms throttle with leading +
  * trailing edge updates for smooth performance.
  *
  * Usage:
  * {{{
  *   // Responsive layout based on viewport width
  *   div(
  *     WindowDimensions.rxInnerWidth.map { width =>
  *       if width < 768 then cls := "mobile-layout"
  *       else cls := "desktop-layout"
  *     }
  *   )
  *
  *   // Track combined dimensions
  *   WindowDimensions.dimensions.run { case (width, height) =>
  *     println(s"Viewport: \${width}x\${height}")
  *   }
  * }}}
  */
object WindowDimensions:

  private val throttleMs = 100 // Suitable interval for resize events

  private class DimensionsVar extends Cancelable:
    private val innerWidthVar  = Rx.variable(dom.window.innerWidth.toInt)
    private val innerHeightVar = Rx.variable(dom.window.innerHeight.toInt)
    private val outerWidthVar  = Rx.variable(dom.window.outerWidth.toInt)
    private val outerHeightVar = Rx.variable(dom.window.outerHeight.toInt)

    private var lastUpdateTime: Double           = 0
    private var scheduledUpdate: js.UndefOr[Int] = js.undefined

    private def updateValues(): Unit =
      innerWidthVar  := dom.window.innerWidth.toInt
      innerHeightVar := dom.window.innerHeight.toInt
      outerWidthVar  := dom.window.outerWidth.toInt
      outerHeightVar := dom.window.outerHeight.toInt

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

    dom.window.addEventListener("resize", handler)

    def getInnerWidth: Int  = innerWidthVar.get
    def getInnerHeight: Int = innerHeightVar.get
    def getOuterWidth: Int  = outerWidthVar.get
    def getOuterHeight: Int = outerHeightVar.get

    def rxInnerWidth: Rx[Int]  = innerWidthVar
    def rxInnerHeight: Rx[Int] = innerHeightVar
    def rxOuterWidth: Rx[Int]  = outerWidthVar
    def rxOuterHeight: Rx[Int] = outerHeightVar

    override def cancel: Unit =
      dom.window.removeEventListener("resize", handler)
      scheduledUpdate.foreach(dom.window.clearTimeout)
      scheduledUpdate = js.undefined

  end DimensionsVar

  private lazy val instance: DimensionsVar = DimensionsVar()

  /**
    * Get current viewport width.
    */
  def innerWidth: Int = instance.getInnerWidth

  /**
    * Get current viewport height.
    */
  def innerHeight: Int = instance.getInnerHeight

  /**
    * Get current full window width (including browser chrome).
    */
  def outerWidth: Int = instance.getOuterWidth

  /**
    * Get current full window height (including browser chrome).
    */
  def outerHeight: Int = instance.getOuterHeight

  /**
    * Reactive stream of viewport width.
    */
  def rxInnerWidth: Rx[Int] = instance.rxInnerWidth

  /**
    * Reactive stream of viewport height.
    */
  def rxInnerHeight: Rx[Int] = instance.rxInnerHeight

  /**
    * Reactive stream of full window width.
    */
  def rxOuterWidth: Rx[Int] = instance.rxOuterWidth

  /**
    * Reactive stream of full window height.
    */
  def rxOuterHeight: Rx[Int] = instance.rxOuterHeight

  /**
    * Reactive stream of combined viewport dimensions (width, height). Emits when either dimension
    * changes.
    */
  def dimensions: Rx[(Int, Int)] = instance.rxInnerWidth.join(instance.rxInnerHeight)

  /**
    * Stop listening to resize events. Call this when the application is shutting down to clean up
    * event listeners.
    */
  def stop(): Unit = instance.cancel

end WindowDimensions
