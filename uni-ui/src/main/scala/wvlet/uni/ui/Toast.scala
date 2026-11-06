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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.collection.mutable
import scala.language.implicitConversions

/** The look of a toast: the CSS modifier is `uni-toast--<cssName>`. */
enum ToastVariant(val cssName: String):
  case Info    extends ToastVariant("info")
  case Success extends ToastVariant("success")
  case Error   extends ToastVariant("error")

/** One message shown by a [[Toaster]]. */
case class Toast(id: Int, message: String, variant: ToastVariant, durationMs: Int)

/**
  * Short, non-blocking messages ("Saved", "Copy failed") in a corner of the window. Mount one
  * `Toaster` per app and call `show` from anywhere that holds it:
  *
  * {{{
  *   val toasts = Toaster()
  *   div(appContent, toasts)
  *   toasts.success("Session saved")
  *   toasts.error("Couldn't reach the server", durationMs = 0) // stays until dismissed
  * }}}
  *
  * Each toast closes after `durationMs` (pausing while the pointer or focus is on it), or on its
  * close button. The region is `section.uni-toaster` with `aria-live="polite"`, rendered at the end
  * of `<body>`; error toasts use `role="alert"` so screen readers announce them right away.
  */
class Toaster extends RxElement:
  private val items  = Rx.variable(Seq.empty[Toast])
  private val timers = mutable.Map.empty[Int, Int]
  private var nextId = 0

  /** The toasts on screen, oldest first. */
  def toasts: Rx[Seq[Toast]] = items

  /**
    * Show a message and return its id, for [[dismiss]].
    *
    * @param durationMs
    *   how long it stays; 0 or less keeps it until it's dismissed
    */
  def show(
      message: String,
      variant: ToastVariant = ToastVariant.Info,
      durationMs: Int = Toaster.defaultDurationMs
  ): Int =
    nextId += 1
    val t = Toast(nextId, message, variant, durationMs)
    items.update(_ :+ t)
    startTimer(t)
    t.id

  def info(message: String, durationMs: Int = Toaster.defaultDurationMs): Int = show(
    message,
    ToastVariant.Info,
    durationMs
  )

  def success(message: String, durationMs: Int = Toaster.defaultDurationMs): Int = show(
    message,
    ToastVariant.Success,
    durationMs
  )

  def error(message: String, durationMs: Int = Toaster.defaultDurationMs): Int = show(
    message,
    ToastVariant.Error,
    durationMs
  )

  def dismiss(id: Int): Unit =
    stopTimer(id)
    items.update(_.filterNot(_.id == id))

  def clear(): Unit =
    timers.keys.toSeq.foreach(stopTimer)
    items := Seq.empty

  private def startTimer(t: Toast): Unit =
    stopTimer(t.id)
    if t.durationMs > 0 then
      timers(t.id) = dom.window.setTimeout(() => dismiss(t.id), t.durationMs.toDouble)

  private def stopTimer(id: Int): Unit = timers.remove(id).foreach(dom.window.clearTimeout)

  private def view(t: Toast): RxElement =
    // Resume only when neither the pointer nor focus is on the toast
    var hovered        = false
    var focused        = false
    def pause(): Unit  = stopTimer(t.id)
    def resume(): Unit =
      if !hovered && !focused then
        startTimer(t)
    div(
      cls -> s"uni-toast uni-toast--${t.variant.cssName}",
      if t.variant == ToastVariant.Error then
        role -> "alert"
      else
        DomNode.empty
      ,
      data("state") -> "open",
      EventListenerBinding(
        "pointerenter",
        _ =>
          hovered = true
          pause()
      ),
      EventListenerBinding(
        "pointerleave",
        _ =>
          hovered = false
          resume()
      ),
      EventListenerBinding(
        "focusin",
        _ =>
          focused = true
          pause()
      ),
      EventListenerBinding(
        "focusout",
        _ =>
          focused = false
          resume()
      ),
      div(cls -> "uni-toast__message", t.message),
      IconButton("Dismiss", "×").small.onClick(() => dismiss(t.id))
    )

  end view

  override def render: RxElement = Embedded(
    Portal.toBody(
      section(
        cls       -> "uni-toaster",
        ariaLabel -> "Notifications",
        ariaLive  -> "polite",
        each(items)(_.id)(view)
      )
    )
  )

  override def beforeUnmount: Unit = timers.keys.toSeq.foreach(stopTimer)

end Toaster

object Toaster:
  val defaultDurationMs = 5000
