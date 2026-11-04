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
import wvlet.uni.rx.RxVar

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/**
  * Configuration for IntersectionObserver.
  *
  * @param root
  *   The element used as the viewport for checking visibility (defaults to browser viewport)
  * @param rootMargin
  *   Margin around the root, e.g., "10px 20px 30px 40px"
  * @param threshold
  *   Percentage of target visibility to trigger callback (0.0 to 1.0)
  */
case class IntersectionConfig(
    root: Option[dom.Element] = None,
    rootMargin: String = "0px",
    threshold: Seq[Double] = Seq(0.0)
)

object IntersectionConfig:
  val default: IntersectionConfig = IntersectionConfig()

/**
  * Entry representing the intersection state of an observed element.
  */
case class IntersectionEntry(
    isIntersecting: Boolean,
    intersectionRatio: Double,
    boundingClientRect: dom.DOMRect,
    rootBounds: Option[dom.DOMRect],
    target: dom.Element
)

/**
  * Binding for IntersectionObserver that updates a boolean RxVar.
  */
case class IntersectionBinding(target: RxVar[Boolean], config: IntersectionConfig) extends DomNode

/**
  * Binding for IntersectionObserver that updates an RxVar with full entry details.
  */
case class IntersectionEntryBinding(
    target: RxVar[Option[IntersectionEntry]],
    config: IntersectionConfig
) extends DomNode

/**
  * Binding for IntersectionObserver that calls a callback once when visible.
  */
case class IntersectionOnceBinding(callback: () => Unit, config: IntersectionConfig) extends DomNode

/**
  * Reactive IntersectionObserver wrapper for visibility detection.
  *
  * Usage:
  * {{{
  *   // Lazy loading
  *   val isVisible = Rx.variable(false)
  *   div(
  *     Intersection.observe(isVisible),
  *     isVisible.map { visible =>
  *       if visible then img(src -> "/heavy-image.jpg")
  *       else div(cls -> "placeholder")
  *     }
  *   )
  *
  *   // One-time lazy load
  *   div(
  *     Intersection.onceVisible(() => analytics.trackView("section")),
  *     "Content here"
  *   )
  * }}}
  */
object Intersection:
  /**
    * Create a reactive binding that updates an RxVar[Boolean] when element visibility changes.
    *
    * @param target
    *   The RxVar to update with visibility state
    * @param config
    *   IntersectionObserver configuration
    */
  def observe(
      target: RxVar[Boolean],
      config: IntersectionConfig = IntersectionConfig.default
  ): DomNode = IntersectionBinding(target, config)

  /**
    * Create a reactive binding that updates an RxVar with full IntersectionEntry details.
    *
    * @param target
    *   The RxVar to update with intersection entry
    * @param config
    *   IntersectionObserver configuration
    */
  def observeEntry(
      target: RxVar[Option[IntersectionEntry]],
      config: IntersectionConfig = IntersectionConfig.default
  ): DomNode = IntersectionEntryBinding(target, config)

  /**
    * Create a binding that calls a callback once when the element becomes visible.
    *
    * The observer is automatically disconnected after the callback is called.
    *
    * @param callback
    *   The callback to invoke when visible
    * @param config
    *   IntersectionObserver configuration
    */
  def onceVisible(
      callback: () => Unit,
      config: IntersectionConfig = IntersectionConfig.default
  ): DomNode = IntersectionOnceBinding(callback, config)

end Intersection

/**
  * Entry representing the size of an observed element.
  */
case class ResizeEntry(contentRect: dom.DOMRect, target: dom.Element)

/**
  * Binding for ResizeObserver that updates an RxVar with (width, height).
  */
case class ResizeBinding(target: RxVar[(Double, Double)]) extends DomNode

/**
  * Binding for ResizeObserver that updates an RxVar with full entry details.
  */
case class ResizeEntryBinding(target: RxVar[Option[ResizeEntry]]) extends DomNode

/**
  * Binding for ResizeObserver with debouncing.
  */
case class ResizeBindingDebounced(target: RxVar[(Double, Double)], debounceMs: Int) extends DomNode

/**
  * Reactive ResizeObserver wrapper for element size tracking.
  *
  * Usage:
  * {{{
  *   // Responsive component
  *   val size = Rx.variable((0.0, 0.0))
  *   div(
  *     Resize.observe(size),
  *     size.map { case (w, _) =>
  *       if w > 768 then "desktop-layout" else "mobile-layout"
  *     }
  *   )
  * }}}
  */
object Resize:
  /**
    * Create a reactive binding that updates an RxVar with (width, height) when element size
    * changes.
    *
    * @param target
    *   The RxVar to update with dimensions
    */
  def observe(target: RxVar[(Double, Double)]): DomNode = ResizeBinding(target)

  /**
    * Create a reactive binding that updates an RxVar with full ResizeEntry details.
    *
    * @param target
    *   The RxVar to update with resize entry
    */
  def observeEntry(target: RxVar[Option[ResizeEntry]]): DomNode = ResizeEntryBinding(target)

  /**
    * Create a debounced reactive binding that limits update frequency.
    *
    * @param target
    *   The RxVar to update with dimensions
    * @param debounceMs
    *   Debounce interval in milliseconds
    */
  def observeDebounced(target: RxVar[(Double, Double)], debounceMs: Int = 100): DomNode =
    ResizeBindingDebounced(target, debounceMs)

end Resize

// Scala.js facades for browser APIs

@js.native
@JSGlobal
class IntersectionObserver(
    callback: js.Function2[js.Array[IntersectionObserverEntry], IntersectionObserver, Unit],
    options: js.UndefOr[IntersectionObserverInit] = js.undefined
) extends js.Object:
  def observe(target: dom.Element): Unit   = js.native
  def unobserve(target: dom.Element): Unit = js.native
  def disconnect(): Unit                   = js.native

@js.native
trait IntersectionObserverEntry extends js.Object:
  def isIntersecting: Boolean         = js.native
  def intersectionRatio: Double       = js.native
  def boundingClientRect: dom.DOMRect = js.native
  def rootBounds: dom.DOMRect | Null  = js.native
  def target: dom.Element             = js.native

trait IntersectionObserverInit extends js.Object:
  var root: js.UndefOr[dom.Element]           = js.undefined
  var rootMargin: js.UndefOr[String]          = js.undefined
  var threshold: js.UndefOr[js.Array[Double]] = js.undefined

object IntersectionObserverInit:
  def apply(config: IntersectionConfig): IntersectionObserverInit =
    val init = new js.Object().asInstanceOf[IntersectionObserverInit]
    config.root.foreach(r => init.root = r)
    init.rootMargin = config.rootMargin
    init.threshold = js.Array(config.threshold*)
    init

@js.native
@JSGlobal
class ResizeObserver(callback: js.Function2[js.Array[ResizeObserverEntry], ResizeObserver, Unit])
    extends js.Object:
  def observe(target: dom.Element): Unit   = js.native
  def unobserve(target: dom.Element): Unit = js.native
  def disconnect(): Unit                   = js.native

@js.native
trait ResizeObserverEntry extends js.Object:
  def contentRect: dom.DOMRect = js.native
  def target: dom.Element      = js.native
