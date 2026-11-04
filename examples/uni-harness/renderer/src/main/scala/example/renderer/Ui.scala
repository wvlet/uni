package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

import scala.language.implicitConversions

/** Small building blocks shared by the panes. */
object Ui:

  /** A ghost icon button with a tooltip, for pane headers. */
  def iconButton(
      label: String,
      icon: RxElement,
      action: () => Unit,
      tipSide: String = "bottom"
  ): RxElement = button(
    data("tip")      -> label,
    data("tip-side") -> tipSide,
    ariaLabel        -> label,
    cls              ->
      "grid size-8 place-items-center rounded-lg text-muted transition hover:bg-hover hover:text-fg active:scale-95",
    onclick -> { () =>
      action()
    },
    icon
  )

  /** A keyboard key cap. */
  def keyCap(k: String): RxElement = span(
    cls ->
      "inline-grid min-w-[18px] place-items-center rounded-[5px] border border-line bg-surface px-1 font-mono text-[10px] leading-4 text-muted shadow-[0_1px_0_var(--h-line)]",
    k
  )

  /** An uppercase section label. */
  def overline(text: String): RxElement = div(
    cls -> "px-1 pb-2 text-[10px] font-semibold tracking-[0.12em] text-faint uppercase",
    text
  )

  def plural(n: Int, word: String): String =
    if n == 1 then
      s"${n} ${word}"
    else
      s"${n} ${word}s"

end Ui
