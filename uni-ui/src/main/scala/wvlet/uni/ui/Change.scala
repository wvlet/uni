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
import wvlet.uni.rx.{Cancelable, RxVar}

/** Who made a change: a pointer (mouse, touch, or pen), the keyboard, app code, or an AI agent. */
enum ChangeSource:
  case Pointer,
    Keyboard,
    Programmatic,
    Agent

object ChangeSource:
  private var scoped: Option[ChangeSource] = None

  /**
    * Attributes every uni-ui change made inside `body` to `source`, whatever event caused it. An
    * agent bridge wraps its actions in `ChangeSource.withSource(ChangeSource.Agent) { ... }`.
    */
  def withSource[A](source: ChangeSource)(body: => A): A =
    val saved = scoped
    scoped = Some(source)
    try body
    finally scoped = saved

  /**
    * The source of a DOM event. A click with `detail == 0` comes from the keyboard (Enter or Space
    * on a button), not from a pointer.
    */
  def of(e: dom.Event): ChangeSource =
    e match
      case _: dom.KeyboardEvent =>
        Keyboard
      case m: dom.MouseEvent if e.`type` == "click" && m.detail == 0 =>
        Keyboard
      case _: dom.MouseEvent =>
        Pointer
      case _ =>
        Programmatic

  private[ui] def resolve(detected: ChangeSource): ChangeSource = scoped.getOrElse(detected)

end ChangeSource

/**
  * Why a change happened, such as `ChangeReason.EscapeKey`. Apps and agent bridges can define their
  * own: `ChangeReason("voice-command")`.
  */
case class ChangeReason(name: String):
  override def toString: String = name

object ChangeReason:
  /** A press on the component itself: a button, a trigger, a tab, a column header. */
  val Press = ChangeReason("press")

  /** A press on an item inside the component: a menu item, an option, a row. */
  val ItemPress = ChangeReason("item-press")

  /** A confirm or cancel button of a dialog. */
  val ActionPress = ChangeReason("action-press")

  val EscapeKey    = ChangeReason("escape-key")
  val OutsidePress = ChangeReason("outside-press")

  /** Arrow keys, Home, End, PageUp, or PageDown. */
  val NavigationKey = ChangeReason("navigation-key")

  /** Typing the first letters of an item. */
  val Typeahead = ChangeReason("typeahead")

  /** Text typed into a field. */
  val Input = ChangeReason("input")

  /** The Delete key, such as on a closable tab. */
  val DeleteKey = ChangeReason("delete-key")

  /** A pointer drag, such as a splitter or a dragged tab. */
  val Drag = ChangeReason("drag")

  /** Focus left the component, as with Tab. */
  val FocusOut = ChangeReason("focus-out")

  /** A reset to the default, such as a double-click on a splitter. */
  val Reset = ChangeReason("reset")

  /** A call from app code, such as `dock.open(id)`. */
  val Imperative = ChangeReason("imperative")

end ChangeReason

/**
  * A change a uni-ui component is about to make to state the app owns (an `RxVar` passed to it).
  * The component's `onChange` handler sees it before it lands, and `cancel()` keeps the old value.
  *
  * {{{
  *   Select(region, regions)(_.label).onChange { d =>
  *     if d.source == ChangeSource.Agent && d.value == Region.Restricted then d.cancel()
  *   }
  * }}}
  *
  * @param key
  *   the state that changes: `open`, `selected`, `value`, `expanded`, `sort`, `size`, or `layout`
  */
class ChangeDetails[+A](
    val key: String,
    val value: A,
    val previous: A,
    val reason: ChangeReason,
    val source: ChangeSource
):
  private var refused = false

  /** Keep the previous value. */
  def cancel(): Unit = refused = true

  def canceled: Boolean = refused

  override def toString: String =
    s"ChangeDetails(${key}: ${previous} -> ${value}, reason=${reason}, source=${source}${
        if refused then
          ", canceled"
        else
          ""
      })"

end ChangeDetails

/** A component's `onChange` handler. */
type ChangeHandler = ChangeDetails[Any] => Unit

/**
  * The reason and source of a change or an action. Inside a uni-ui callback, such as
  * `Button.onClick` or `MenuItem.onSelect`, [[ChangeCause.current]] tells what triggered it:
  *
  * {{{
  *   Button("Delete").onClick { () =>
  *     if ChangeCause.current.source == ChangeSource.Agent then confirmFirst() else delete()
  *   }
  * }}}
  */
case class ChangeCause(reason: ChangeReason, source: ChangeSource)

object ChangeCause:
  private var ambient: Option[ChangeCause] = None

  private[ui] def of(reason: ChangeReason, e: dom.Event): ChangeCause = ChangeCause(
    reason,
    ChangeSource.of(e)
  )

  private[ui] def imperative: ChangeCause = ChangeCause(
    ChangeReason.Imperative,
    ChangeSource.Programmatic
  )

  /**
    * Makes `cause` the cause of changes inside `body` that don't know their own, such as an
    * overlay's `onDismiss` called by [[base.Dismiss]].
    */
  private[ui] def during[A](cause: ChangeCause)(body: => A): A =
    val saved = ambient
    ambient = Some(cause)
    try body
    finally ambient = saved

  /**
    * The cause of the uni-ui action or change being handled, such as a click on a button or Escape
    * on a dialog. Outside one it is `imperative` with the `Programmatic` source (or the source that
    * [[ChangeSource.withSource]] sets).
    */
  def current: ChangeCause = ambientOr(imperative)

  /** The cause set by [[during]], or `default` outside of it. */
  private[ui] def ambientOr(default: ChangeCause): ChangeCause = resolved(
    ambient.getOrElse(default)
  )

  private[ui] def resolved(c: ChangeCause): ChangeCause = c.copy(source =
    ChangeSource.resolve(c.source)
  )

end ChangeCause

/** The single path through which uni-ui components write app-owned state. */
private[ui] object ChangeGate:
  val noHandler: ChangeHandler = _ => ()

  /**
    * Asks `handler` about setting `target` to `value`, and writes it unless the handler canceled.
    * Returns true when `target` holds `value` afterwards: the change was accepted, or the value was
    * already current (which is not reported). Returns false when the handler canceled it.
    * `beforeWrite` runs once the change is accepted, just before the write.
    */
  def write[A](
      target: RxVar[A],
      key: String,
      value: A,
      cause: ChangeCause,
      handler: ChangeHandler,
      beforeWrite: () => Unit = () => ()
  ): Boolean =
    val previous = target.get
    if previous == value then
      true
    else
      val d = ChangeDetails(key, value, previous, cause.reason, ChangeSource.resolve(cause.source))
      handler(d)
      if d.canceled then
        false
      else
        beforeWrite()
        target := value
        true

end ChangeGate

/**
  * A stand-in for an app's `RxVar`, for code that writes the variable itself, such as a two-way
  * `value.bind` or a `PaneResize` drag. A write asks the change handler first and reaches `target`
  * only when accepted; a refused write leaves this variable unchanged too. [[follow]] keeps the
  * stand-in in step with writes the app makes to `target`.
  *
  * @param cause
  *   the cause of a write, read when it happens
  * @param holding
  *   true while writes stay in this variable as a draft, such as during an IME composition;
  *   [[commit]] sends the draft on afterwards
  */
private[ui] class GatedVar[A](
    target: RxVar[A],
    key: String,
    handler: () => ChangeHandler,
    cause: () => ChangeCause,
    holding: () => Boolean = () => false
) extends RxVar[A](target.get):

  override def update(updater: A => A, force: Boolean = false): Unit =
    val v = updater(get)
    if holding() || ChangeGate.write(target, key, v, cause(), handler()) then
      super.update(_ => v, force)

  /** Sends a held draft to `target`. Returns false when it was refused, leaving `target` as is. */
  def commit(): Boolean =
    val accepted = ChangeGate.write(target, key, get, cause(), handler())
    if !accepted then
      super.update(_ => target.get)
    accepted

  /**
    * Keeps this variable equal to `target`, for app writes. Call it on mount and cancel the result
    * on unmount.
    */
  def follow(): Cancelable = target.foreach { t =>
    if t != get && !holding() then
      super.update(_ => t)
  }

end GatedVar
