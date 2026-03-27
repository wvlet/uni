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
import wvlet.uni.rx.{Cancelable, OnError, OnNext, Rx, RxRunner, RxVar}

import scala.scalajs.js

/**
  * Configuration for CSS-based element transitions.
  *
  * @param name
  *   The CSS class prefix for transition classes (e.g., "fade" produces "fade-enter-from",
  *   "fade-enter-active", etc.)
  * @param duration
  *   Optional explicit timeout in milliseconds. If set, the transition will end after this duration
  *   regardless of CSS transition/animation events.
  * @param appear
  *   Whether to animate on initial render when visible is initially true
  */
case class TransitionConfig(
    name: String = "v",
    duration: Option[Int] = None,
    appear: Boolean = false
):
  def withName(name: String): TransitionConfig      = copy(name = name)
  def withDuration(duration: Int): TransitionConfig = copy(duration = Some(duration))
  def noDuration: TransitionConfig                  = copy(duration = None)
  def withAppear: TransitionConfig                  = copy(appear = true)

/**
  * CSS-class-based transition system driven by `Rx[Boolean]` visibility.
  *
  * Manages enter/leave CSS class sequences on a wrapper `<div>` element, controlling visibility
  * with `display: none`. The wrapper element always exists in the DOM.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   val isOpen = Rx.variable(false)
  *
  *   // Named transition (requires corresponding CSS classes)
  *   Transition("fade", isOpen)(
  *     div(cls -> "modal", "Hello")
  *   )
  *
  *   // Built-in fade
  *   Transition.fade(isOpen)(div("Fading content"))
  *
  *   // Built-in slide
  *   Transition.slide(isOpen)(div("Sliding content"))
  *
  *   // With config
  *   Transition(TransitionConfig(name = "fade", duration = Some(300), appear = true), isOpen)(
  *     div("Content")
  *   )
  * }}}
  *
  * CSS class sequence:
  *   - Enter: `{name}-enter-from` + `{name}-enter-active` -> next frame: remove `-enter-from`, add
  *     `-enter-to` -> on `transitionend`: remove `-enter-active` and `-enter-to`
  *   - Leave: `{name}-leave-from` + `{name}-leave-active` -> next frame: remove `-leave-from`, add
  *     `-leave-to` -> on `transitionend`: remove classes, set `display: none`
  */
object Transition:

  /**
    * Create a transition with the given CSS class prefix.
    *
    * @param name
    *   CSS class prefix (e.g., "fade" produces "fade-enter-from", etc.)
    * @param visible
    *   Reactive boolean controlling visibility
    * @param children
    *   Child nodes to wrap
    */
  def apply(name: String, visible: Rx[Boolean])(children: DomNode*): RxElement = TransitionElement(
    TransitionConfig(name = name),
    visible,
    children
  )

  /**
    * Create a transition with a full configuration.
    *
    * @param config
    *   Transition configuration
    * @param visible
    *   Reactive boolean controlling visibility
    * @param children
    *   Child nodes to wrap
    */
  def apply(config: TransitionConfig, visible: Rx[Boolean])(children: DomNode*): RxElement =
    TransitionElement(config, visible, children)

  /**
    * Built-in fade transition. Requires CSS classes: `fade-enter-active`, `fade-leave-active` with
    * `transition: opacity`, and `fade-enter-from`, `fade-leave-to` with `opacity: 0`.
    */
  def fade(visible: Rx[Boolean])(children: DomNode*): RxElement = TransitionElement(
    TransitionConfig(name = "fade"),
    visible,
    children
  )

  /**
    * Built-in slide transition. Requires CSS classes: `slide-enter-active`, `slide-leave-active`
    * with `transition: transform`, and `slide-enter-from`, `slide-leave-to` with `transform:
    * translateY(-10px)` or similar.
    */
  def slide(visible: Rx[Boolean])(children: DomNode*): RxElement = TransitionElement(
    TransitionConfig(name = "slide"),
    visible,
    children
  )

end Transition

/**
  * Internal: Manages CSS transition class sequences on a wrapper div element.
  */
private class TransitionElement(
    config: TransitionConfig,
    visible: Rx[Boolean],
    children: Seq[DomNode]
) extends RxElement:

  private var containerRef: Option[dom.HTMLElement]                         = None
  private var rxCancelable: Cancelable                                      = Cancelable.empty
  private var pendingTimeout: js.UndefOr[Int]                               = js.undefined
  private var pendingRaf: js.UndefOr[Int]                                   = js.undefined
  private var transitionListener: js.UndefOr[js.Function1[dom.Event, Unit]] = js.undefined
  private var isFirstRender: Boolean                                        = true

  override def onMount(node: Any): Unit =
    node match
      case elem: dom.HTMLElement =>
        containerRef = Some(elem)

        // Set initial visibility
        visible match
          case rv: RxVar[Boolean @unchecked] =>
            if !rv.get then
              elem.style.display = "none"
            else if config.appear then
              runEnter(elem)
          case _ =>
            ()

        // Subscribe to visibility changes
        rxCancelable =
          RxRunner.runContinuously(visible) { ev =>
            ev match
              case OnNext(show: Boolean @unchecked) =>
                if isFirstRender then
                  isFirstRender = false
                else
                  cancelPending()
                  if show then
                    runEnter(elem)
                  else
                    runLeave(elem)
              case OnError(e) =>
                ()
              case _ =>
                ()
          }
      case _ =>
        ()

  override def beforeUnmount: Unit =
    cancelPending()
    rxCancelable.cancel

  private def cancelPending(): Unit =
    pendingTimeout.foreach(id => dom.window.clearTimeout(id))
    pendingTimeout = js.undefined
    pendingRaf.foreach(id => dom.window.cancelAnimationFrame(id))
    pendingRaf = js.undefined
    containerRef.foreach { elem =>
      transitionListener.foreach { listener =>
        elem.removeEventListener("transitionend", listener)
        elem.removeEventListener("animationend", listener)
      }
    }
    transitionListener = js.undefined

  private def runEnter(elem: dom.HTMLElement): Unit =
    val name = config.name
    // Show element
    elem.style.display = ""
    // Apply enter-from and enter-active classes
    elem.classList.add(s"${name}-enter-from")
    elem.classList.add(s"${name}-enter-active")

    // Double rAF for frame-precise class changes
    pendingRaf = dom
      .window
      .requestAnimationFrame { _ =>
        pendingRaf = dom
          .window
          .requestAnimationFrame { _ =>
            elem.classList.remove(s"${name}-enter-from")
            elem.classList.add(s"${name}-enter-to")

            // Wait for transition end
            waitForTransitionEnd(elem) { () =>
              elem.classList.remove(s"${name}-enter-active")
              elem.classList.remove(s"${name}-enter-to")
            }
          }
      }

  private def runLeave(elem: dom.HTMLElement): Unit =
    val name = config.name
    // Apply leave-from and leave-active classes
    elem.classList.add(s"${name}-leave-from")
    elem.classList.add(s"${name}-leave-active")

    // Double rAF for frame-precise class changes
    pendingRaf = dom
      .window
      .requestAnimationFrame { _ =>
        pendingRaf = dom
          .window
          .requestAnimationFrame { _ =>
            elem.classList.remove(s"${name}-leave-from")
            elem.classList.add(s"${name}-leave-to")

            // Wait for transition end
            waitForTransitionEnd(elem) { () =>
              elem.classList.remove(s"${name}-leave-active")
              elem.classList.remove(s"${name}-leave-to")
              elem.style.display = "none"
            }
          }
      }

  private def waitForTransitionEnd(elem: dom.HTMLElement)(onEnd: () => Unit): Unit =
    val timeoutMs = config.duration.getOrElse(5000)

    val listener: js.Function1[dom.Event, Unit] =
      (_: dom.Event) =>
        pendingTimeout.foreach(id => dom.window.clearTimeout(id))
        pendingTimeout = js.undefined
        elem.removeEventListener("transitionend", transitionListener.get)
        elem.removeEventListener("animationend", transitionListener.get)
        transitionListener = js.undefined
        onEnd()
    transitionListener = listener

    elem.addEventListener("transitionend", listener)
    elem.addEventListener("animationend", listener)

    // Safety timeout fallback
    pendingTimeout = dom
      .window
      .setTimeout(
        () =>
          transitionListener.foreach { l =>
            elem.removeEventListener("transitionend", l)
            elem.removeEventListener("animationend", l)
          }
          transitionListener = js.undefined
          onEnd()
        ,
        timeoutMs
      )

  end waitForTransitionEnd

  override def render: RxElement =
    import HtmlTags.tag
    tag("div")(children*)

end TransitionElement
