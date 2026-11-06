package example.renderer

import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.ui.{IconButton, Kbd}

import scala.language.implicitConversions

/** Small building blocks shared by the panes. */
object Ui:

  /** A ghost icon button with a tooltip, for pane headers. */
  def iconButton(
      label: String,
      icon: RxElement,
      action: () => Unit,
      tipSide: String = "bottom"
  ): RxElement =
    IconButton(label, icon).onClick(action)(data("tip") -> label, data("tip-side") -> tipSide)

  /** A keyboard key cap. */
  def keyCap(k: String): RxElement = Kbd(k)

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
