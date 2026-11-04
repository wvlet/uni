package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}

import scala.language.implicitConversions

/** The side of a pane that faces the chat: where its border and resize handle go. */
enum Edge:
  case Left,
    Right

/**
  * A side pane that folds to zero width with a transition. Its content keeps the full width, so it
  * never reflows while the pane opens or closes. Used by the sidebar and the dock.
  *
  * @param resize
  *   a drag handle on the inner edge, for a resizable pane
  */
class FoldablePane(
    open: Rx[Boolean],
    width: Rx[Int],
    edge: Edge,
    resize: Option[ResizeHandle] = None
)(content: RxElement*)
    extends RxElement:

  private val dragging: Rx[Boolean] = resize.map(_.dragging).getOrElse(Rx.const(false))

  override def render: RxElement = aside(
    cls ->
      Rx.join(open, dragging)
        .map { (isOpen, isDragging) =>
          val border =
            (edge, isOpen) match
              case (_, false) =>
                "border-0"
              case (Edge.Right, true) =>
                "border-r"
              case (Edge.Left, true) =>
                "border-l"
          val motion =
            if isDragging then
              ""
            else
              "transition-[width] duration-300 ease-out"
          s"relative shrink-0 overflow-hidden border-line bg-panel backdrop-blur-xl ${border} ${motion}"
        },
    styleAttr ->
      Rx.join(open, width)
        .map((isOpen, w) =>
          s"width: ${
              if isOpen then
                w
              else
                0
            }px"
        ),
    resize.map(h => open.map(isOpen => when(isOpen, h))),
    div(cls -> "flex h-full flex-col", styleAttr -> width.map(w => s"width: ${w}px"), content)
  )

end FoldablePane

/**
  * The visible grip on a pane's inner edge. Dragging and double-click reset come from uni-dom's
  * `PaneResize`; this class only places and styles the handle.
  */
class ResizeHandle(width: RxVar[Int], range: SizeRange, edge: Edge, label: String)
    extends RxElement:
  private val resize = PaneResize(
    width,
    range,
    // A handle on the right grows the pane as it moves right; one on the left, as it moves left
    if edge == Edge.Right then
      ResizeDirection.Right
    else
      ResizeDirection.Left
  )

  def dragging: Rx[Boolean] = resize.dragging

  override def render: RxElement =
    val side =
      if edge == Edge.Right then
        "right-0"
      else
        "left-0"
    div(
      ariaLabel -> label,
      titleAttr -> "Drag to resize, double-click to reset",
      cls       ->
        s"group absolute top-0 bottom-0 ${side} z-20 w-2 cursor-col-resize touch-none select-none",
      resize.bind,
      span(
        cls ->
          dragging.map { on =>
            val look =
              if on then
                "opacity-100"
              else
                "opacity-0 group-hover:opacity-60"
            s"absolute top-0 bottom-0 ${side} w-[2px] bg-brand transition-opacity ${look}"
          }
      )
    )

end ResizeHandle
