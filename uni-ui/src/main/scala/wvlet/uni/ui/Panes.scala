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
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.language.implicitConversions

/**
  * The side of a pane that faces the main content: where its border and its [[Splitter]] go. A left
  * sidebar's inner edge is `Right`; a right-hand dock's inner edge is `Left`.
  */
enum Edge(val cssName: String):
  case Left  extends Edge("left")
  case Right extends Edge("right")

/**
  * A side pane that folds to zero width with a transition. Its content keeps the full width, so it
  * never reflows while the pane opens or closes. Give it a [[Splitter]] to make it resizable.
  *
  * {{{
  *   val open  = Rx.variable(true)
  *   val width = Rx.variable(272)
  *   val range = SizeRange(min = 208, default = 272, max = 480)
  *   FoldablePane(open, width = width, edge = Edge.Right, content = Seq(Sidebar()))
  *     .withSplitter(Splitter(width, range, Edge.Right, "Resize sidebar"))
  * }}}
  *
  * The pane is an `<aside class="uni-pane">` with `data-edge` and `data-state="open|closed"`.
  */
class FoldablePane private (
    open: Rx[Boolean],
    width: Rx[Int],
    edge: Edge,
    content: Seq[DomNode],
    splitter: Option[Splitter]
) extends RxElement:

  /** A [[Splitter]] on the pane's inner edge that resizes it. */
  def withSplitter(s: Splitter): FoldablePane =
    new FoldablePane(open, width, edge, content, Some(s))

  def noSplitter(): FoldablePane = new FoldablePane(open, width, edge, content, None)

  private val dragging: Rx[Boolean] = splitter.map(_.dragging).getOrElse(Rx.const(false))

  override def render: RxElement = aside(
    cls           -> "uni-pane",
    data("slot")  -> "pane",
    data("edge")  -> edge.cssName,
    data("state") ->
      open.map(isOpen =>
        if isOpen then
          "open"
        else
          "closed"
      ),
    // Follow the pointer without the width transition while dragging
    data("dragging") -> dragging.map(_.toString),
    styleAttr        ->
      Rx.join(open, width)
        .map((isOpen, w) =>
          s"width: ${
              if isOpen then
                w
              else
                0
            }px"
        ),
    splitter.map(s => open.map(isOpen => when(isOpen, s))),
    div(
      cls          -> "uni-pane__content",
      data("slot") -> "pane-content",
      styleAttr    -> width.map(w => s"width: ${w}px"),
      content
    )
  )

end FoldablePane

object FoldablePane:
  // `new` is required here: the `apply` below replaces the constructor proxy
  def apply(open: Rx[Boolean], width: Rx[Int], edge: Edge, content: Seq[DomNode]): FoldablePane =
    new FoldablePane(open, width, edge, content, None)

/**
  * A grip on a pane's inner edge that resizes it: drag it, or focus it and press the arrow keys.
  * Double-click (or Enter) restores `range.default`. It sits on `edge` of the nearest positioned
  * ancestor, such as a [[FoldablePane]].
  *
  * @param size
  *   the pane's width in CSS pixels
  * @param label
  *   the accessible name, such as "Resize sidebar"
  */
class Splitter(size: RxVar[Int], range: SizeRange, edge: Edge, label: String) extends RxElement:

  private var changeHandler: ChangeHandler = ChangeGate.noHandler

  /**
    * Called before the size changes; `cancel()` keeps the current size. Key: `size`. It sets the
    * handler on this splitter and returns it, so a [[FoldablePane]] given the splitter sees it too.
    */
  def onChange(f: ChangeHandler): Splitter =
    changeHandler = f
    this

  // PaneResize writes the size on its own during a drag, so it writes this stand-in
  private val gated = GatedVar(
    size,
    "size",
    () => changeHandler,
    () => ChangeCause.ambientOr(ChangeCause(ChangeReason.Drag, ChangeSource.Pointer))
  )

  private val resize = PaneResize(
    gated,
    range,
    // A grip on the right grows the pane as it moves right; one on the left, as it moves left
    if edge == Edge.Right then
      ResizeDirection.Right
    else
      ResizeDirection.Left
    ,
    // A double-click restores the default size, reported as a reset like Enter
    onDoubleClick = Some(() => setSize(range.default, ChangeReason.Reset, ChangeSource.Pointer))
  )

  private var following: Cancelable = Cancelable.empty

  // Follow app writes to `size` while the grip is rendered
  override def beforeRender: Unit =
    following.cancel
    following = gated.follow()

  override def beforeUnmount: Unit =
    following.cancel
    following = Cancelable.empty

  /** True while the grip is being dragged. */
  def dragging: Rx[Boolean] = resize.dragging

  private def setSize(
      v: Int,
      reason: ChangeReason,
      source: ChangeSource = ChangeSource.Keyboard
  ): Unit = ChangeCause.during(ChangeCause(reason, source))(gated := v)

  private def onKey(e: dom.KeyboardEvent): Unit =
    val towardsContent =
      if edge == Edge.Right then
        "ArrowRight"
      else
        "ArrowLeft"
    val step =
      if e.shiftKey then
        Splitter.largeStep
      else
        Splitter.step
    e.key match
      case "ArrowLeft" | "ArrowRight" =>
        e.preventDefault()
        val delta =
          if e.key == towardsContent then
            step
          else
            -step
        setSize(range.clamp(gated.get + delta), ChangeReason.NavigationKey)
      case "Home" =>
        e.preventDefault()
        setSize(range.min, ChangeReason.NavigationKey)
      case "End" =>
        e.preventDefault()
        setSize(range.max, ChangeReason.NavigationKey)
      case "Enter" =>
        e.preventDefault()
        setSize(range.default, ChangeReason.Reset)
      case _ =>

  end onKey

  override def render: RxElement = div(
    cls              -> "uni-splitter",
    data("slot")     -> "splitter",
    data("edge")     -> edge.cssName,
    data("dragging") -> dragging.map(_.toString),
    ariaLabel        -> label,
    titleAttr        -> "Drag to resize, double-click to reset",
    tabindex         -> "0",
    ariaValuemin     -> range.min.toString,
    ariaValuemax     -> range.max.toString,
    ariaValuenow     -> size.map(_.toString),
    // A listener, so an app's own `onkeydown` modifier runs alongside the arrow keys
    EventListenerBinding("keydown", e => onKey(e.asInstanceOf[dom.KeyboardEvent])),
    resize.bind
  )

end Splitter

object Splitter:
  /** Pixels per arrow key press, and with Shift. */
  val step: Int      = 16
  val largeStep: Int = 64
