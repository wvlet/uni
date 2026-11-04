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
import wvlet.uni.cli.term.*
import wvlet.uni.rx.Cancelable

import scala.collection.mutable
import scala.scalajs.js

/**
  * Colors for [[TerminalView]], as CSS color strings. `ansi` holds the 16 base colors: black, red,
  * green, yellow, blue, magenta, cyan, white, then their bright variants.
  */
case class TerminalTheme(
    foreground: String,
    background: String,
    cursor: String,
    selection: String,
    ansi: Seq[String]
):
  require(ansi.length == 16, s"A terminal theme needs 16 ANSI colors, but got ${ansi.length}")

  def withForeground(color: String): TerminalTheme = copy(foreground = color)
  def withBackground(color: String): TerminalTheme = copy(background = color)
  def withCursor(color: String): TerminalTheme     = copy(cursor = color)
  def withSelection(color: String): TerminalTheme  = copy(selection = color)
  def withAnsi(colors: Seq[String]): TerminalTheme = copy(ansi = colors)

  private lazy val palette: Array[String] = TerminalTheme.palette256(ansi)

  /**
    * The CSS color of a 256-color palette index
    */
  def paletteColor(index: Int): String = palette(index & 0xff)

object TerminalTheme:
  val dark: TerminalTheme = TerminalTheme(
    foreground = "#d4d4d4",
    background = "#1e1e1e",
    cursor = "#d4d4d4",
    selection = "rgba(120, 160, 255, 0.35)",
    ansi = Seq(
      "#000000",
      "#cd3131",
      "#0dbc79",
      "#e5e510",
      "#2472c8",
      "#bc3fbc",
      "#11a8cd",
      "#e5e5e5",
      "#666666",
      "#f14c4c",
      "#23d18b",
      "#f5f543",
      "#3b8eea",
      "#d670d6",
      "#29b8db",
      "#ffffff"
    )
  )

  val light: TerminalTheme = TerminalTheme(
    foreground = "#333333",
    background = "#ffffff",
    cursor = "#333333",
    selection = "rgba(0, 100, 255, 0.25)",
    ansi = Seq(
      "#000000",
      "#cd3131",
      "#00bc00",
      "#949800",
      "#0451a5",
      "#bc05bc",
      "#0598bc",
      "#555555",
      "#666666",
      "#cd3131",
      "#14ce14",
      "#b5ba00",
      "#0451a5",
      "#bc05bc",
      "#0598bc",
      "#a5a5a5"
    )
  )

  // The 16 base colors, a 6x6x6 color cube, and a 24-step gray ramp, as xterm defines them
  private def palette256(ansi: Seq[String]): Array[String] =
    val levels = Array(0, 95, 135, 175, 215, 255)
    Array.tabulate(256) { i =>
      if i < 16 then
        ansi(i)
      else if i < 232 then
        val c = i - 16
        s"rgb(${levels(c / 36)}, ${levels((c / 6) % 6)}, ${levels(c % 6)})"
      else
        val v = 8 + (i - 232) * 10
        s"rgb(${v}, ${v}, ${v})"
    }

end TerminalTheme

/**
  * Configuration of a [[TerminalView]]
  */
case class TerminalViewConfig(
    theme: TerminalTheme = TerminalTheme.dark,
    fontFamily: String = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
    fontSize: Double = 13,
    lineHeight: Double = 1.25,
    padding: Int = 4,
    macOptionIsMeta: Boolean = false
):
  def withTheme(theme: TerminalTheme): TerminalViewConfig    = copy(theme = theme)
  def withFontFamily(fontFamily: String): TerminalViewConfig = copy(fontFamily = fontFamily)
  def withFontSize(fontSize: Double): TerminalViewConfig     = copy(fontSize = fontSize)
  def withLineHeight(lineHeight: Double): TerminalViewConfig = copy(lineHeight = lineHeight)
  def withPadding(padding: Int): TerminalViewConfig          = copy(padding = padding)

  /**
    * On macOS, send Option+key as Meta+key (ESC + key, for readline word movement) instead of the
    * character Option types. Off by default, so layouts that type `@`, `[`, or `|` with Option
    * work.
    */
  def withMacOptionIsMeta(enabled: Boolean): TerminalViewConfig = copy(macOptionIsMeta = enabled)

/**
  * Draws a [[TerminalEmulator]] in the DOM and sends keyboard, paste, and mouse input to it.
  *
  * Each row is an element of styled text runs, and only rows that changed are redrawn, at most once
  * per animation frame. Lines in the scrollback stay in the DOM above the screen, so the pane
  * scrolls, selects, and copies like ordinary text. Keyboard input goes through a hidden textarea,
  * so IME composition works. The view fits the terminal to its own size: give it a height (it fills
  * its container) and register [[onResize]] to tell the program.
  *
  * {{{
  *   val term = TerminalEmulator()
  *   val view = TerminalView(term)
  *   view.onResize((cols, rows) => pty.resize(cols, rows))
  *   PtyProcess.connect(term, pty)
  *   div(styleAttr -> "height: 400px", view)
  * }}}
  */
class TerminalView(
    val terminal: TerminalEmulator,
    config: TerminalViewConfig = TerminalViewConfig()
) extends RxElement:
  private val resizeListeners                     = mutable.ArrayBuffer.empty[(Int, Int) => Unit]
  private var linkHandler: Option[String => Unit] = None

  private var container: dom.HTMLElement = null
  // Scrollback rows, hidden while the alternate screen shows, then the screen rows
  private var scrollbackEl: dom.HTMLElement = null
  private var screenEl: dom.HTMLElement     = null
  private var input: dom.html.TextArea      = null
  private var cellWidth                     = 8.0
  private var cellHeight                    = 16.0

  // Rendering state
  private val screenRows         = mutable.ArrayBuffer.empty[dom.HTMLElement]
  private var scrollbackRows     = 0
  private var renderedGeneration = -1L
  private var renderedScrollback = -1L
  private var renderedTotal      = 0L
  private var cursorRowDrawn     = -1
  private var frameRequested     = false
  private var focused            = false
  private var composing          = false
  private var mouseButton        = MouseButton.None
  private val cleanups           = mutable.ArrayBuffer.empty[() => Unit]

  /**
    * Register a callback for size changes, in cells. Pass the size to the program, for example with
    * [[PtyProcess.resize]].
    */
  def onResize(listener: (Int, Int) => Unit): Cancelable =
    resizeListeners += listener
    Cancelable(() => resizeListeners -= listener)

  /**
    * Make OSC 8 hyperlinks clickable with Ctrl (Cmd on macOS) + click. The handler receives the
    * link's URI; it should check the scheme before opening it.
    */
  def onLinkClick(handler: String => Unit): TerminalView =
    linkHandler = Some(handler)
    this

  /**
    * Move keyboard focus to the terminal
    */
  def focus(): Unit =
    if input != null then
      input.focus()

  override def render: RxElement =
    import all.*
    div(cls -> "uni-terminal", styleAttr -> containerStyle)

  private def containerStyle: String =
    val t = config.theme
    s"position: relative; height: 100%; width: 100%; box-sizing: border-box; overflow-y: auto; overflow-x: hidden; " +
      s"padding: ${config.padding}px; background: ${t.background}; color: ${t.foreground}; " +
      s"font-family: ${config.fontFamily}; font-size: ${config.fontSize}px; " +
      s"line-height: ${config.lineHeight}; cursor: text; outline: none;"

  override def onMount(node: Any): Unit =
    node match
      case el: dom.HTMLElement =>
        container = el
        setup()
      case _ =>
        ()

  override def beforeUnmount: Unit =
    cleanups.foreach(_())
    cleanups.clear()
    container = null

  private def listen(
      target: dom.EventTarget,
      event: String,
      f: js.Function1[dom.Event, Unit]
  ): Unit =
    target.addEventListener(event, f)
    cleanups += (() => target.removeEventListener(event, f))

  private def setup(): Unit =
    val doc = dom.document
    // A remounted view draws everything again
    screenRows.clear()
    scrollbackRows = 0
    renderedGeneration = -1L
    renderedScrollback = -1L
    cursorRowDrawn = -1
    // Selection color for text in the pane
    val style = doc.createElement("style")
    style.textContent =
      s".uni-terminal ::selection { background: ${config.theme.selection}; }" +
        ".uni-terminal .uni-term-row { white-space: pre; overflow: hidden; }" +
        ".uni-terminal .uni-term-link { text-decoration: underline dotted; }"
    container.appendChild(style)

    scrollbackEl = doc.createElement("div").asInstanceOf[dom.HTMLElement]
    scrollbackEl.className = "uni-term-scrollback"
    container.appendChild(scrollbackEl)
    screenEl = doc.createElement("div").asInstanceOf[dom.HTMLElement]
    screenEl.className = "uni-term-screen"
    container.appendChild(screenEl)

    input = doc.createElement("textarea").asInstanceOf[dom.html.TextArea]
    input.className = "uni-term-input"
    input.setAttribute("aria-label", "Terminal input")
    input.setAttribute("autocapitalize", "off")
    input.setAttribute("autocomplete", "off")
    input.setAttribute("autocorrect", "off")
    input.setAttribute("spellcheck", "false")
    input.style.cssText =
      "position: absolute; left: 0; top: 0; width: 1px; height: 1px; padding: 0; border: 0; " +
        "opacity: 0; resize: none; overflow: hidden; white-space: nowrap; z-index: -1;"
    container.appendChild(input)

    measure()
    installInput()
    installMouse()

    val changed = terminal.onChange(() => scheduleRender())
    cleanups += (() => changed.cancel)

    val observer = ResizeObserver((_, _) => fit())
    observer.observe(container)
    cleanups += (() => observer.disconnect())
    // Measure again once web fonts finish loading
    val fonts = doc.asInstanceOf[js.Dynamic].fonts
    if !js.isUndefined(fonts) then
      fonts
        .ready
        .`then`((_: js.Any) =>
          if container != null then
            measure()
            fit()
        )
    fit()
    renderNow()

  end setup

  private def measure(): Unit =
    val probe = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
    probe.style.cssText = "position: absolute; visibility: hidden; white-space: pre;"
    probe.textContent = "W" * 32
    container.appendChild(probe)
    val rect = probe.getBoundingClientRect()
    container.removeChild(probe)
    if rect.width > 0 then
      cellWidth = rect.width / 32
    cellHeight = math.max(1.0, math.ceil(config.fontSize * config.lineHeight))
    container.style.setProperty("--uni-term-cw", s"${cellWidth}px")

  // Fit the terminal to the pane
  private def fit(): Unit =
    if container != null then
      val width  = container.clientWidth - 2 * config.padding
      val height = container.clientHeight - 2 * config.padding
      if width > 0 && height > 0 then
        val cols = math.max(2, math.floor(width / cellWidth).toInt)
        val rows = math.max(1, math.floor(height / cellHeight).toInt)
        if cols != terminal.cols || rows != terminal.rows then
          terminal.resize(cols, rows)
          resizeListeners.foreach(_(cols, rows))

  // ---- Rendering ----

  private def scheduleRender(): Unit =
    if !frameRequested then
      frameRequested = true
      dom.window.requestAnimationFrame(_ => renderNow())

  private[dom] def renderNow(): Unit =
    frameRequested = false
    if container != null then
      val screen   = terminal.screen
      val atBottom = container.scrollTop + container.clientHeight >= container.scrollHeight - 2
      if screen.scrollbackVersion != renderedScrollback then
        rebuildScrollback(screen)
      else
        appendScrollback(screen)
      scrollbackEl.style.display =
        if screen.isAlternate then
          "none"
        else
          ""
      if screen.generation != renderedGeneration then
        rebuildScreen(screen)
      else
        var row = 0
        while row < screen.rows do
          if screen.isDirty(row) || row == cursorRowDrawn || row == screen.cursorRow then
            drawRow(screenRows(row), screen.line(row), row)
          row += 1
      screen.clearDirty()
      cursorRowDrawn = screen.cursorRow
      placeInput(screen)
      if atBottom then
        container.scrollTop = container.scrollHeight.toDouble

  private def rebuildScrollback(screen: Screen): Unit =
    scrollbackEl.textContent = ""
    var i = 0
    while i < screen.scrollback.size do
      scrollbackEl.appendChild(staticRow(screen.scrollback(i)))
      i += 1
    scrollbackRows = screen.scrollback.size
    renderedScrollback = screen.scrollbackVersion
    renderedTotal = screen.scrollbackTotal

  // Add lines that scrolled off since the last frame, and drop lines the scrollback evicted
  private def appendScrollback(screen: Screen): Unit =
    val added =
      math.min(screen.scrollbackTotal - renderedTotal, screen.scrollback.size.toLong).toInt
    renderedTotal = screen.scrollbackTotal
    var i = screen.scrollback.size - added
    while i < screen.scrollback.size do
      scrollbackEl.appendChild(staticRow(screen.scrollback(i)))
      i += 1
    scrollbackRows += added
    while scrollbackRows > screen.scrollback.size do
      scrollbackEl.removeChild(scrollbackEl.firstChild)
      scrollbackRows -= 1

  // Redraw every visible row, e.g. after a resize or a switch to the alternate screen
  private def rebuildScreen(screen: Screen): Unit =
    screenEl.textContent = ""
    screenRows.clear()
    var row = 0
    while row < screen.rows do
      val el = newRow()
      screenEl.appendChild(el)
      screenRows += el
      drawRow(el, screen.line(row), row)
      row += 1
    renderedGeneration = screen.generation

  private def newRow(): dom.HTMLElement =
    val el = dom.document.createElement("div").asInstanceOf[dom.HTMLElement]
    el.className = "uni-term-row"
    el.style.height = s"${cellHeight}px"
    el

  private def staticRow(line: Line): dom.HTMLElement =
    val el = newRow()
    drawRow(el, line, -1)
    el

  // Draw a line as runs of cells that share a style. `row` is the screen row, or -1 for scrollback.
  private def drawRow(el: dom.HTMLElement, line: Line, row: Int): Unit =
    val screen    = terminal.screen
    val cursorCol =
      if row >= 0 && row == screen.cursorRow && screen.isCursorVisible then
        screen.cursorCol
      else
        -1
    el.textContent = ""
    val text          = StringBuilder()
    var runStyle      = ""
    var runLink       = 0
    var runCursor     = false
    def flush(): Unit =
      if text.nonEmpty then
        el.appendChild(span(text.toString, runStyle, runLink, runCursor))
        text.clear()
    var col = 0
    val n   = line.length
    while col < n do
      val flags = line.flags(col)
      if (flags & CellFlags.WideTail) == 0 then
        val isCursor = col == cursorCol
        val wide     = (flags & CellFlags.Wide) != 0
        val style    = cellStyle(line, col, isCursor, wide)
        val link     = line.link(col)
        if style != runStyle || link != runLink || isCursor != runCursor || wide then
          flush()
          runStyle = style
          runLink = link
          runCursor = isCursor
        text.append(line.cellText(col))
        if wide then
          flush()
          runStyle = ""
      col += 1
    flush()

  end drawRow

  private def span(text: String, style: String, link: Int, isCursor: Boolean): dom.Node =
    if style.isEmpty && link == 0 && !isCursor then
      dom.document.createTextNode(text)
    else
      val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
      s.textContent = text
      if style.nonEmpty then
        s.style.cssText = style
      if isCursor then
        s.className = "uni-term-cursor"
      if link != 0 then
        terminal
          .hyperlink(link)
          .foreach { uri =>
            s.className = (s.className + " uni-term-link").trim
            s.setAttribute("data-uri", uri)
            s.setAttribute("title", uri)
          }
      s

  private def color(c: Int, default: String): String =
    if CellColor.isPalette(c) then
      config.theme.paletteColor(CellColor.index(c))
    else if CellColor.isRgb(c) then
      f"#${CellColor.rgbValue(c)}%06x"
    else
      default

  private def cellStyle(line: Line, col: Int, isCursor: Boolean, wide: Boolean): String =
    val theme   = config.theme
    val flags   = line.flags(col)
    val screen  = terminal.screen
    var fg      = color(line.fg(col), theme.foreground)
    var bg      = color(line.bg(col), "")
    val inverse = ((flags & CellFlags.Inverse) != 0) != screen.isReverseVideo
    if inverse then
      val f = fg
      fg =
        if bg.isEmpty then
          theme.background
        else
          bg
      bg = f
    val blockCursor = isCursor && focused && screen.cursorShape == CursorStyle.Block
    if blockCursor then
      bg = theme.cursor
      fg = theme.background
    val sb = StringBuilder()
    if fg != theme.foreground then
      sb.append(s"color: ${fg};")
    if bg.nonEmpty then
      sb.append(s"background: ${bg};")
    if (flags & CellFlags.Bold) != 0 then
      sb.append("font-weight: bold;")
    if (flags & CellFlags.Dim) != 0 then
      sb.append("opacity: 0.6;")
    if (flags & CellFlags.Italic) != 0 then
      sb.append("font-style: italic;")
    if (flags & CellFlags.Invisible) != 0 then
      sb.append("color: transparent;")
    val decorations = Seq(
      (CellFlags.Underline, "underline"),
      (CellFlags.Strikethrough, "line-through"),
      (CellFlags.Overline, "overline")
    ).collect {
      case (f, d) if (flags & f) != 0 =>
        d
    }
    if decorations.nonEmpty then
      sb.append(s"text-decoration: ${decorations.mkString(" ")};")
    if wide then
      sb.append("display: inline-block; width: calc(var(--uni-term-cw) * 2);")
    if isCursor && !blockCursor then
      val shadow =
        if !focused then
          s"inset 0 0 0 1px ${theme.cursor}"
        else if screen.cursorShape == CursorStyle.Bar then
          s"inset 2px 0 0 ${theme.cursor}"
        else
          s"inset 0 -2px 0 ${theme.cursor}"
      sb.append(s"box-shadow: ${shadow};")
    sb.toString

  end cellStyle

  // Keep the hidden textarea at the cursor, so the IME candidate window opens there
  private def placeInput(screen: Screen): Unit =
    val firstRow = screenRows.headOption
    firstRow.foreach { el =>
      input.style.left = s"${config.padding + screen.cursorCol * cellWidth}px"
      input.style.top = s"${el.offsetTop + screen.cursorRow * cellHeight}px"
    }

  // ---- Input ----

  private def installInput(): Unit =
    listen(input, "keydown", (e: dom.Event) => onKeyDown(e.asInstanceOf[dom.KeyboardEvent]))
    listen(input, "compositionstart", (_: dom.Event) => composing = true)
    listen(
      input,
      "compositionend",
      (e: dom.Event) =>
        composing = false
        val data = e.asInstanceOf[dom.CompositionEvent].data
        input.value = ""
        if data != null && data.nonEmpty then
          terminal.sendText(data)
    )
    listen(
      input,
      "input",
      (e: dom.Event) =>
        val composingNow = composing || e.asInstanceOf[js.Dynamic].isComposing == (true: js.Any)
        if !composingNow then
          val text = input.value
          input.value = ""
          if text.nonEmpty then
            terminal.sendText(text)
    )
    listen(
      input,
      "paste",
      (e: dom.Event) =>
        val ce   = e.asInstanceOf[dom.ClipboardEvent]
        val text = ce.clipboardData.getData("text/plain")
        e.preventDefault()
        if text.nonEmpty then
          terminal.paste(text)
    )
    listen(
      input,
      "focus",
      (_: dom.Event) =>
        focused = true
        terminal.setFocused(true)
        redrawCursor()
    )
    listen(
      input,
      "blur",
      (_: dom.Event) =>
        focused = false
        terminal.setFocused(false)
        redrawCursor()
    )
    // Keys pressed while the pane (not the textarea) has focus, e.g. after selecting text
    listen(
      container,
      "keydown",
      (e: dom.Event) =>
        if e.target != input then
          val ke = e.asInstanceOf[dom.KeyboardEvent]
          if !isCopy(ke) then
            input.focus()
            onKeyDown(ke)
    )

  end installInput

  // renderNow always redraws the cursor row
  private def redrawCursor(): Unit = scheduleRender()

  private def isCopy(e: dom.KeyboardEvent): Boolean =
    val key = e.key.toLowerCase
    key == "c" && (e.metaKey || (e.ctrlKey && e.shiftKey)) && selectedText.nonEmpty

  private def selectedText: String =
    val sel = dom.window.getSelection()
    if sel == null then
      ""
    else
      sel.toString

  private def onKeyDown(e: dom.KeyboardEvent): Unit =
    if !(composing || Keyboard.isComposing(e)) then
      if e.metaKey || (e.ctrlKey && e.shiftKey && (e.key == "C" || e.key == "V")) then
        // Leave Cmd shortcuts and Ctrl+Shift+C/V to the browser (copy, paste, app menus)
        ()
      else
        val printable = e.key.codePointCount(0, e.key.length) == 1
        // AltGr (reported as Ctrl+Alt on Windows) and macOS Option compose characters such as `@`
        val composesText =
          e.getModifierState("AltGraph") || (e.altKey && isMac && !config.macOptionIsMeta)
        if printable && ((!e.ctrlKey && !e.altKey) || composesText) then
          // Typed text arrives through the input event, which also handles dead keys
          ()
        else
          // Alt+letter is Meta+letter; use the physical key since macOS Option changes the character
          val name =
            if e.altKey && printable && e.code.startsWith("Key") && e.code.length == 4 then
              val c = e.code.charAt(3)
              if e.shiftKey then
                c.toString
              else
                c.toLower.toString
            else
              e.key
          if terminal.sendKey(name, e.ctrlKey, e.altKey, e.shiftKey && !printable, false) then
            e.preventDefault()
            scrollToBottom()

  private lazy val isMac: Boolean = dom.window.navigator.platform.toLowerCase.contains("mac")

  private def scrollToBottom(): Unit = container.scrollTop = container.scrollHeight.toDouble

  // ---- Mouse ----

  private def installMouse(): Unit =
    listen(
      container,
      "mousedown",
      (e: dom.Event) =>
        val me = e.asInstanceOf[dom.MouseEvent]
        if tracking(me) then
          me.preventDefault()
          mouseButton = buttonOf(me.button)
          sendMouse(me, MouseAction.Press, mouseButton)
          input.focus()
    )
    listen(
      dom.window,
      "mouseup",
      (e: dom.Event) =>
        val me = e.asInstanceOf[dom.MouseEvent]
        if mouseButton != MouseButton.None && tracking(me) then
          sendMouse(me, MouseAction.Release, mouseButton)
        mouseButton = MouseButton.None
    )
    listen(
      container,
      "mousemove",
      (e: dom.Event) =>
        val me = e.asInstanceOf[dom.MouseEvent]
        if tracking(me) then
          sendMouse(me, MouseAction.Move, mouseButton)
    )
    listen(
      container,
      "click",
      (e: dom.Event) =>
        val me      = e.asInstanceOf[dom.MouseEvent]
        val linkUri =
          me.target match
            case el: dom.Element if el.classList.contains("uni-term-link") =>
              Option(el.getAttribute("data-uri"))
            case _ =>
              None
        (linkUri, linkHandler) match
          case (Some(uri), Some(handler)) if me.metaKey || me.ctrlKey =>
            me.preventDefault()
            handler(uri)
          case _ =>
            // Focus for typing, unless the user just selected text to copy
            if selectedText.isEmpty then
              input.focus()
    )
    listen(
      container,
      "wheel",
      (e: dom.Event) =>
        val we     = e.asInstanceOf[dom.WheelEvent]
        val screen = terminal.screen
        if tracking(we) && we.deltaY != 0 then
          we.preventDefault()
          val button =
            if we.deltaY < 0 then
              MouseButton.WheelUp
            else
              MouseButton.WheelDown
          sendMouse(we, MouseAction.Press, button)
        else if screen.isAlternate && we.deltaY != 0 then
          // Full-screen programs without mouse support (less, man) scroll with arrow keys
          we.preventDefault()
          val key =
            if we.deltaY < 0 then
              "ArrowUp"
            else
              "ArrowDown"
          (0 until 3).foreach(_ => terminal.sendKey(key))
    )

  end installMouse

  // Shift bypasses mouse tracking so the user can still select text
  private def tracking(e: dom.MouseEvent): Boolean =
    terminal.inputModes.mouseTracking != MouseTracking.Off && !e.shiftKey

  private def buttonOf(b: Int): MouseButton =
    b match
      case 0 =>
        MouseButton.Left
      case 1 =>
        MouseButton.Middle
      case 2 =>
        MouseButton.Right
      case _ =>
        MouseButton.None

  private def sendMouse(e: dom.MouseEvent, action: MouseAction, button: MouseButton): Unit =
    screenRows
      .headOption
      .foreach { first =>
        val rect   = first.getBoundingClientRect()
        val col    = math.floor((e.clientX - rect.left) / cellWidth).toInt
        val row    = math.floor((e.clientY - rect.top) / cellHeight).toInt
        val screen = terminal.screen
        terminal.sendMouse(
          MouseEvent(
            action,
            button,
            row = math.max(0, math.min(screen.rows - 1, row)),
            col = math.max(0, math.min(screen.cols - 1, col)),
            shift = e.shiftKey,
            alt = e.altKey,
            ctrl = e.ctrlKey
          )
        )
      }

end TerminalView
