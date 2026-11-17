package example.renderer

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.ui.{IconButton, Kbd, Tooltip}
import wvlet.uni.ui.base.Side

import scala.language.implicitConversions
import scala.scalajs.js

/** Small building blocks shared by the panes. */
object Ui:

  /** A ghost icon button with a tooltip, for pane headers. */
  def iconButton(
      label: String,
      icon: RxElement,
      action: () => Unit,
      tipSide: Side = Side.Bottom
  ): RxElement = Tooltip(label, trigger = IconButton(label, icon).onClick(action)).withSide(tipSide)

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

  /** A bridge that the Electron preload script exposes on `window`, absent in a browser. */
  def preloadBridge(name: String): Option[js.Dynamic] =
    val b = dom.window.asInstanceOf[js.Dynamic].selectDynamic(name)
    if js.isUndefined(b) || b == null then
      None
    else
      Some(b)

  /**
    * Open a link outside the app: in the system browser through the preload's `uniShell` in
    * Electron, in a new tab in a browser.
    */
  def openLink(url: String): Unit =
    preloadBridge("uniShell") match
      case Some(shell) =>
        shell.openExternal(url)
      case None =>
        dom.window.open(url, "_blank", "noopener,noreferrer")

end Ui
