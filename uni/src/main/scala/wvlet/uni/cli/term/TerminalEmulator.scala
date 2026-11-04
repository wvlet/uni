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
package wvlet.uni.cli.term

import wvlet.uni.cli.Key
import wvlet.uni.rx.Cancelable

import scala.collection.mutable

/**
  * How a program asked to receive mouse events
  */
enum MouseTracking:
  /**
    * No mouse events: the terminal handles the mouse itself (selection, scrolling)
    */
  case Off

  /**
    * Button presses and releases (mode 1000)
    */
  case Click

  /**
    * Presses, releases, and motion while a button is held (mode 1002)
    */
  case Drag

  /**
    * All motion (mode 1003)
    */
  case Any

/**
  * Terminal modes that change how user input is encoded, set by the program running in the terminal
  */
case class InputModes(
    applicationCursorKeys: Boolean = false,
    applicationKeypad: Boolean = false,
    bracketedPaste: Boolean = false,
    mouseTracking: MouseTracking = MouseTracking.Off,
    sgrMouse: Boolean = false,
    focusEvents: Boolean = false
)

/**
  * Configuration of a [[TerminalEmulator]]
  */
case class TerminalConfig(cols: Int = 80, rows: Int = 24, scrollbackLines: Int = 1000):
  def withCols(cols: Int): TerminalConfig                       = copy(cols = cols)
  def withRows(rows: Int): TerminalConfig                       = copy(rows = rows)
  def withSize(cols: Int, rows: Int): TerminalConfig            = copy(cols = cols, rows = rows)
  def withScrollbackLines(scrollbackLines: Int): TerminalConfig = copy(scrollbackLines =
    scrollbackLines
  )

  def noScrollback(): TerminalConfig = copy(scrollbackLines = 0)

/**
  * A terminal emulator compatible with xterm (`TERM=xterm-256color`), in pure Scala.
  *
  * Feed it what a program writes to its terminal with [[write]], and read the result from
  * [[screen]]. Replies the terminal owes the program (cursor position reports, device attributes)
  * and encoded user input go to the [[onData]] callback, which should write them to the program's
  * input, typically a [[PtyProcess]]. A renderer such as `wvlet.uni.dom.TerminalView` draws the
  * screen.
  *
  * {{{
  *   val term = TerminalEmulator(TerminalConfig(cols = 40, rows = 5))
  *   term.write("Hello\r\n\u001b[1mWorld\u001b[0m")
  *   term.screen.text // "Hello\nWorld"
  * }}}
  */
class TerminalEmulator(config: TerminalConfig = TerminalConfig()):
  private val _screen = Screen(config.cols, config.rows, config.scrollbackLines)
  private val parser  = EscapeParser(Handler)

  private var _inputModes         = InputModes()
  private var _title              = ""
  private var _workingDirectory   = ""
  private var _synchronizedOutput = false
  private val dataListeners       = mutable.ArrayBuffer.empty[String => Unit]
  private val changeListeners     = mutable.ArrayBuffer.empty[() => Unit]
  private val titleListeners      = mutable.ArrayBuffer.empty[String => Unit]
  private val bellListeners       = mutable.ArrayBuffer.empty[() => Unit]
  private val hyperlinks          = mutable.ArrayBuffer.empty[String]
  private val hyperlinkIds        = mutable.HashMap.empty[String, Int]
  private var focused             = true

  /**
    * The cell grid and cursor
    */
  def screen: Screen = _screen

  def cols: Int = _screen.cols
  def rows: Int = _screen.rows

  /**
    * The window title the program set with OSC 0 or OSC 2
    */
  def title: String = _title

  /**
    * The working directory the shell reported with OSC 7 (a `file://` URL), or "" when unknown
    */
  def workingDirectory: String = _workingDirectory

  /**
    * The modes that change how [[input]] encodes keys, paste, and mouse events
    */
  def inputModes: InputModes = _inputModes

  /**
    * True while the program is drawing a frame in synchronized output mode (2026). Renderers should
    * hold the screen until it ends; [[onChange]] does not fire until then.
    */
  def isSynchronizedOutput: Boolean = _synchronizedOutput

  /**
    * The URI of a hyperlink id stored in a cell (see [[Line.link]])
    */
  def hyperlink(id: Int): Option[String] =
    if id > 0 && id <= hyperlinks.length then
      Some(hyperlinks(id - 1))
    else
      None

  /**
    * Encodes keys, paste, and mouse events for the program, following the current [[inputModes]]
    */
  val input: InputEncoder = InputEncoder(() => _inputModes)

  /**
    * Register a callback for bytes to send to the program: replies to queries, and user input
    * passed to [[sendKey]], [[paste]], and the other input methods. Cancel the result to remove the
    * callback.
    */
  def onData(listener: String => Unit): Cancelable = register(dataListeners, listener)

  /**
    * Register a callback that runs after the screen changed
    */
  def onChange(listener: () => Unit): Cancelable = register(changeListeners, listener)

  /**
    * Register a callback for title changes
    */
  def onTitleChange(listener: String => Unit): Cancelable = register(titleListeners, listener)

  /**
    * Register a callback for the bell (BEL)
    */
  def onBell(listener: () => Unit): Cancelable = register(bellListeners, listener)

  /**
    * Process program output given as UTF-8 bytes
    */
  def write(bytes: Array[Byte]): Unit =
    parser.feed(bytes)
    notifyChange()

  /**
    * Process program output given as text
    */
  def write(text: String): Unit =
    parser.feed(text)
    notifyChange()

  /**
    * Change the size. The program should also be told, for example with [[PtyProcess.resize]].
    */
  def resize(cols: Int, rows: Int): Unit =
    _screen.resize(cols, rows)
    notifyChange()

  /**
    * Return to the power-on state
    */
  def reset(): Unit =
    _screen.reset()
    _inputModes = InputModes()
    _synchronizedOutput = false
    notifyChange()

  /**
    * Send a key press to the program
    */
  def sendKey(key: Key): Unit = send(input.key(key))

  /**
    * Send a key press given as a W3C `KeyboardEvent.key` name ("a", "Enter", "ArrowUp", "F5") with
    * modifiers
    */
  def sendKey(
      key: String,
      ctrl: Boolean = false,
      alt: Boolean = false,
      shift: Boolean = false,
      meta: Boolean = false
  ): Boolean =
    input.key(key, ctrl, alt, shift, meta) match
      case Some(data) =>
        send(data)
        true
      case None =>
        false

  /**
    * Send typed text, such as the result of an IME composition
    */
  def sendText(text: String): Unit = send(text)

  /**
    * Send pasted text, bracketed when the program enabled bracketed paste
    */
  def paste(text: String): Unit = send(input.paste(text))

  /**
    * Send a mouse event when the program tracks the mouse. Returns false when it does not, so the
    * caller can handle the event itself.
    */
  def sendMouse(event: MouseEvent): Boolean =
    input.mouse(event) match
      case Some(data) =>
        send(data)
        true
      case None =>
        false

  /**
    * Tell the program the terminal gained or lost focus, if it asked (mode 1004)
    */
  def setFocused(isFocused: Boolean): Unit =
    if isFocused != focused then
      focused = isFocused
      if _inputModes.focusEvents then
        send(
          if isFocused then
            "\u001b[I"
          else
            "\u001b[O"
        )

  private def register[A](listeners: mutable.ArrayBuffer[A], listener: A): Cancelable =
    listeners += listener
    Cancelable(() => listeners -= listener)

  private def send(data: String): Unit =
    if data.nonEmpty then
      dataListeners.foreach(_(data))

  private def notifyChange(): Unit =
    if !_synchronizedOutput then
      changeListeners.foreach(_())

  private def setTitle(t: String): Unit =
    _title = t
    titleListeners.foreach(_(t))

  private def hyperlinkId(uri: String): Int = hyperlinkIds.getOrElseUpdate(
    uri, {
      hyperlinks += uri
      hyperlinks.length
    }
  )

  // ---- Escape sequence handling ----

  private object Handler extends EscapeHandler:
    private def s = _screen

    override def print(codePoint: Int): Unit = s.print(codePoint)

    override def execute(code: Int): Unit =
      code match
        case 0x07 =>
          bellListeners.foreach(_())
        case 0x08 =>
          s.backspace()
        case 0x09 =>
          s.tab(1)
        case 0x0a | 0x0b | 0x0c =>
          s.lineFeed()
        case 0x0d =>
          s.carriageReturn()
        case 0x0e =>
          s.activeCharset = 1
        case 0x0f =>
          s.activeCharset = 0
        case _ =>
          ()

    override def esc(intermediates: String, finalChar: Char): Unit =
      intermediates match
        case "" =>
          finalChar match
            case '7' =>
              s.saveCursor()
            case '8' =>
              s.restoreCursor()
            case 'D' =>
              s.index()
            case 'E' =>
              s.carriageReturn()
              s.index()
            case 'H' =>
              s.setTabStop()
            case 'M' =>
              s.reverseIndex()
            case 'c' =>
              reset()
            case '=' =>
              _inputModes = _inputModes.copy(applicationKeypad = true)
            case '>' =>
              _inputModes = _inputModes.copy(applicationKeypad = false)
            case _ =>
              ()
        case "#" if finalChar == '8' =>
          s.fillWithE()
        case "(" | ")" =>
          val g =
            if intermediates == "(" then
              0
            else
              1
          s.charsets(g) = finalChar == '0'
        case _ =>
          ()

    override def osc(data: String): Unit =
      val sep            = data.indexOf(';')
      val (command, arg) =
        if sep < 0 then
          (data, "")
        else
          (data.substring(0, sep), data.substring(sep + 1))
      command match
        case "0" | "2" =>
          setTitle(arg)
        case "7" =>
          _workingDirectory = arg
        case "8" =>
          // OSC 8 ; params ; uri: start a link, or end it with an empty uri
          val uriStart = arg.indexOf(';')
          val uri      =
            if uriStart < 0 then
              ""
            else
              arg.substring(uriStart + 1)
          s.penLink =
            if uri.isEmpty then
              0
            else
              hyperlinkId(uri)
        case _ =>
          // OSC 52 (clipboard) and others are ignored
          ()

    override def csi(p: CsiParams, prefix: Char, intermediates: String, finalChar: Char): Unit =
      def n(i: Int = 0): Int = math.max(1, p(i, 1))
      if prefix == 0 && intermediates.isEmpty then
        finalChar match
          case '@' =>
            s.insertChars(n())
          case 'A' =>
            s.cursorUp(n())
          case 'B' | 'e' =>
            s.cursorDown(n())
          case 'C' | 'a' =>
            s.cursorForward(n())
          case 'D' =>
            s.cursorBackward(n())
          case 'E' =>
            s.cursorDown(n())
            s.carriageReturn()
          case 'F' =>
            s.cursorUp(n())
            s.carriageReturn()
          case 'G' | '`' =>
            s.moveToColumn(n() - 1)
          case 'H' | 'f' =>
            s.moveTo(n(0) - 1, n(1) - 1)
          case 'I' =>
            s.tab(n())
          case 'J' =>
            s.eraseInDisplay(p(0))
          case 'K' =>
            s.eraseInLine(p(0))
          case 'L' =>
            s.insertLines(n())
          case 'M' =>
            s.deleteLines(n())
          case 'P' =>
            s.deleteChars(n())
          case 'S' =>
            s.scrollUp(n())
          case 'T' =>
            s.scrollDown(n())
          case 'X' =>
            s.eraseChars(n())
          case 'Z' =>
            s.backTab(n())
          case 'b' =>
            s.repeatLast(n())
          case 'c' =>
            // Primary device attributes: a VT100 with advanced video
            if p(0) == 0 then
              send("\u001b[?1;2c")
          case 'd' =>
            s.moveToRow(n() - 1)
          case 'g' =>
            s.clearTabStop(p(0))
          case 'h' =>
            setAnsiModes(p, on = true)
          case 'l' =>
            setAnsiModes(p, on = false)
          case 'm' =>
            sgr(p)
          case 'n' =>
            deviceStatus(p(0))
          case 'r' =>
            s.setScrollRegion(n(0) - 1, p(1, s.rows) - 1)
          case 's' =>
            s.saveCursor()
          case 'u' =>
            s.restoreCursor()
          case _ =>
            ()
      else if prefix == '?' && intermediates.isEmpty then
        finalChar match
          case 'h' =>
            setDecModes(p, on = true)
          case 'l' =>
            setDecModes(p, on = false)
          case _ =>
            ()
      else if prefix == '>' && intermediates.isEmpty && finalChar == 'c' then
        // Secondary device attributes, in xterm's format
        send("\u001b[>0;276;0c")
      else if prefix == 0 && intermediates == " " && finalChar == 'q' then
        // DECSCUSR: 0-2 block, 3-4 underline, 5-6 bar; odd values blink
        val style = p(0)
        s.cursorStyle =
          if style <= 2 then
            CursorStyle.Block
          else if style <= 4 then
            CursorStyle.Underline
          else
            CursorStyle.Bar
        s.cursorBlink = style == 0 || style % 2 == 1
      else if prefix == 0 && intermediates == "!" && finalChar == 'p' then
        s.softReset()
        _inputModes = InputModes()
      end if

    end csi

    private def deviceStatus(code: Int): Unit =
      code match
        case 5 =>
          send("\u001b[0n")
        case 6 =>
          send(s"\u001b[${s.cursorRow - s.originRow + 1};${s.cursorCol + 1}R")
        case _ =>
          ()

    private def setAnsiModes(p: CsiParams, on: Boolean): Unit =
      for i <- 0 until p.size do
        p(i) match
          case 4 =>
            s.insertMode = on
          case 20 =>
            s.newLineMode = on
          case _ =>
            ()

    private def setDecModes(p: CsiParams, on: Boolean): Unit =
      for i <- 0 until p.size do
        p(i) match
          case 1 =>
            _inputModes = _inputModes.copy(applicationCursorKeys = on)
          case 5 =>
            s.reverseVideo = on
            s.markAllDirty()
          case 6 =>
            s.originMode = on
            s.moveTo(0, 0)
          case 7 =>
            s.autoWrap = on
          case 12 =>
            s.cursorBlink = on
          case 25 =>
            s.cursorVisible = on
          case 47 | 1047 =>
            if on then
              s.enterAlternate(clear = false)
            else
              s.exitAlternate()
          case 1048 =>
            if on then
              s.saveCursor()
            else
              s.restoreCursor()
          case 1049 =>
            if on then
              s.saveCursor()
              s.enterAlternate(clear = true)
            else
              s.exitAlternate()
              s.restoreCursor()
          case 9 | 1000 =>
            setMouse(on, MouseTracking.Click)
          case 1002 =>
            setMouse(on, MouseTracking.Drag)
          case 1003 =>
            setMouse(on, MouseTracking.Any)
          case 1004 =>
            _inputModes = _inputModes.copy(focusEvents = on)
          case 1006 =>
            _inputModes = _inputModes.copy(sgrMouse = on)
          case 2004 =>
            _inputModes = _inputModes.copy(bracketedPaste = on)
          case 2026 =>
            _synchronizedOutput = on
          case _ =>
            ()

    private def setMouse(on: Boolean, tracking: MouseTracking): Unit =
      _inputModes = _inputModes.copy(mouseTracking =
        if on then
          tracking
        else
          MouseTracking.Off
      )

    // Select graphic rendition: colors and text attributes
    private def sgr(p: CsiParams): Unit =
      if p.size == 0 then
        s.penFg = CellColor.Default
        s.penBg = CellColor.Default
        s.penFlags = 0
      else
        var i = 0
        while i < p.size do
          val code = p(i)
          // Skip over sub-parameters this code does not use
          var next = i + 1
          while next < p.size && p.isSubParam(next) do
            next += 1
          code match
            case 0 =>
              s.penFg = CellColor.Default
              s.penBg = CellColor.Default
              s.penFlags = 0
            case 1 =>
              s.penFlags |= CellFlags.Bold
            case 2 =>
              s.penFlags |= CellFlags.Dim
            case 3 =>
              s.penFlags |= CellFlags.Italic
            case 4 =>
              // 4:0 turns underline off; other styles (curly, dotted) show as underline
              if p.isSubParam(i + 1) && p(i + 1) == 0 then
                s.penFlags &= ~CellFlags.Underline
              else
                s.penFlags |= CellFlags.Underline
            case 5 | 6 =>
              s.penFlags |= CellFlags.Blink
            case 7 =>
              s.penFlags |= CellFlags.Inverse
            case 8 =>
              s.penFlags |= CellFlags.Invisible
            case 9 =>
              s.penFlags |= CellFlags.Strikethrough
            case 21 =>
              s.penFlags |= CellFlags.Underline
            case 22 =>
              s.penFlags &= ~(CellFlags.Bold | CellFlags.Dim)
            case 23 =>
              s.penFlags &= ~CellFlags.Italic
            case 24 =>
              s.penFlags &= ~CellFlags.Underline
            case 25 =>
              s.penFlags &= ~CellFlags.Blink
            case 27 =>
              s.penFlags &= ~CellFlags.Inverse
            case 28 =>
              s.penFlags &= ~CellFlags.Invisible
            case 29 =>
              s.penFlags &= ~CellFlags.Strikethrough
            case 53 =>
              s.penFlags |= CellFlags.Overline
            case 55 =>
              s.penFlags &= ~CellFlags.Overline
            case c if c >= 30 && c <= 37 =>
              s.penFg = CellColor.palette(c - 30)
            case 39 =>
              s.penFg = CellColor.Default
            case c if c >= 40 && c <= 47 =>
              s.penBg = CellColor.palette(c - 40)
            case 49 =>
              s.penBg = CellColor.Default
            case c if c >= 90 && c <= 97 =>
              s.penFg = CellColor.palette(c - 90 + 8)
            case c if c >= 100 && c <= 107 =>
              s.penBg = CellColor.palette(c - 100 + 8)
            case 38 | 48 | 58 =>
              val (color, end) = extendedColor(p, i, next)
              next = end
              if color >= 0 then
                if code == 38 then
                  s.penFg = color
                else if code == 48 then
                  s.penBg = color
                // 58 (underline color) is parsed but not drawn
            case _ =>
              ()
          end match
          i = next
        end while

    /**
      * Read an extended color starting at the 38/48/58 code at index i. Returns the color (or -1)
      * and the index of the next code.
      */
    private def extendedColor(p: CsiParams, i: Int, subEnd: Int): (Int, Int) =
      if subEnd > i + 1 then
        // Colon form: 38:5:n, 38:2:r:g:b, or 38:2:colorspace:r:g:b
        val kind  = p(i + 1)
        val count = subEnd - i - 2
        if kind == 5 && count >= 1 then
          (CellColor.palette(p(i + 2)), subEnd)
        else if kind == 2 && count >= 3 then
          val o =
            if count >= 4 then
              i + 3
            else
              i + 2
          (CellColor.rgb(p(o), p(o + 1), p(o + 2)), subEnd)
        else
          (-1, subEnd)
      else
        // Semicolon form: 38;5;n or 38;2;r;g;b
        p(i + 1, -1) match
          case 5 if i + 2 < p.size =>
            (CellColor.palette(p(i + 2)), i + 3)
          case 2 if i + 4 < p.size =>
            (CellColor.rgb(p(i + 2), p(i + 3), p(i + 4)), i + 5)
          case _ =>
            (-1, p.size)

  end Handler

end TerminalEmulator
