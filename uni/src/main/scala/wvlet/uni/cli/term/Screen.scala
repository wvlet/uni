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

import wvlet.uni.cli.DisplayWidth

import scala.collection.mutable.ArrayBuffer

/**
  * Lines that scrolled off the top of the normal screen, oldest first, up to `capacity` lines
  */
final class Scrollback private[term] (val capacity: Int):
  private val ring  = new Array[Line](math.max(capacity, 0))
  private var start = 0
  private var count = 0

  def size: Int = count

  /**
    * The i-th line, where 0 is the oldest
    */
  def apply(i: Int): Line =
    require(i >= 0 && i < count, s"Scrollback index out of range: ${i}")
    ring((start + i) % capacity)

  private[term] def push(line: Line): Unit =
    if capacity > 0 then
      if count < capacity then
        ring((start + count) % capacity) = line
        count += 1
      else
        ring(start) = line
        start = (start + 1) % capacity

  private[term] def popNewest(): Line =
    count -= 1
    val i    = (start + count) % capacity
    val line = ring(i)
    ring(i) = null
    line

  private[term] def clear(): Unit =
    java.util.Arrays.fill(ring.asInstanceOf[Array[AnyRef]], null)
    start = 0
    count = 0

end Scrollback

/**
  * The terminal's cell grid and cursor: what a renderer draws. [[TerminalEmulator]] updates it as
  * it decodes program output.
  *
  * There are two screens. The normal screen keeps lines that scroll off the top in [[scrollback]].
  * The alternate screen, which full-screen programs such as vim and less switch to, has none.
  */
final class Screen private[term] (initialCols: Int, initialRows: Int, scrollbackLines: Int):
  import Screen.*

  private var _cols = math.max(1, initialCols)
  private var _rows = math.max(1, initialRows)

  private val normalLines    = ArrayBuffer.fill(_rows)(Line(_cols, CellColor.Default))
  private var alternateLines = ArrayBuffer.fill(_rows)(Line(_cols, CellColor.Default))
  private var lines          = normalLines
  private var _isAlternate   = false

  /**
    * Lines that scrolled off the top of the normal screen
    */
  val scrollback: Scrollback = Scrollback(scrollbackLines)

  private var _scrollbackTotal   = 0L
  private var _generation        = 0L
  private var _scrollbackVersion = 0L
  private var dirty              = Array.fill(_rows)(true)

  // Cursor
  private var _cursorRow                     = 0
  private var _cursorCol                     = 0
  private[term] var wrapPending              = false
  private[term] var cursorVisible            = true
  private[term] var cursorStyle: CursorStyle = CursorStyle.Block
  private[term] var cursorBlink: Boolean     = true
  private[term] var reverseVideo: Boolean    = false

  // Pen: the attributes newly written cells get
  private[term] var penFg    = CellColor.Default
  private[term] var penBg    = CellColor.Default
  private[term] var penFlags = 0
  private[term] var penLink  = 0

  // Modes
  private[term] var originMode  = false
  private[term] var autoWrap    = true
  private[term] var insertMode  = false
  private[term] var newLineMode = false

  private var scrollTop    = 0
  private var scrollBottom = _rows - 1
  private var tabStops     = defaultTabStops(_cols)

  // Character sets: G0 and G1, each either ASCII or DEC special graphics (line drawing)
  private[term] val charsets      = Array(false, false)
  private[term] var activeCharset = 0
  private var lastPrinted         = 0

  private var savedNormal: SavedCursor    = SavedCursor()
  private var savedAlternate: SavedCursor = SavedCursor()

  def cols: Int = _cols
  def rows: Int = _rows

  /**
    * The cursor's row, from 0 at the top of the screen
    */
  def cursorRow: Int = _cursorRow

  /**
    * The cursor's column, from 0
    */
  def cursorCol: Int = _cursorCol

  /**
    * False when the program hid the cursor (DECTCEM)
    */
  def isCursorVisible: Boolean = cursorVisible

  /**
    * The cursor shape the program asked for (DECSCUSR)
    */
  def cursorShape: CursorStyle = cursorStyle

  /**
    * True when the program asked for a blinking cursor
    */
  def isCursorBlinking: Boolean = cursorBlink

  /**
    * True when the program turned on reverse video for the whole screen (DECSCNM)
    */
  def isReverseVideo: Boolean = reverseVideo

  /**
    * True while the alternate screen is shown
    */
  def isAlternate: Boolean = _isAlternate

  /**
    * A visible line, from 0 at the top
    */
  def line(row: Int): Line = lines(row)

  /**
    * The text of a visible line, without trailing spaces
    */
  def lineText(row: Int): String = lines(row).text

  /**
    * The visible text, one line per row, without trailing spaces or trailing empty rows
    */
  def text: String =
    val texts = (0 until _rows).map(lineText)
    texts.reverse.dropWhile(_.isEmpty).reverse.mkString("\n")

  /**
    * How many lines have ever entered the scrollback. A renderer compares it with the value it saw
    * last to find newly scrolled-off lines.
    */
  def scrollbackTotal: Long = _scrollbackTotal

  /**
    * Changes when the visible rows change in a way that dirty rows cannot describe: a resize, a
    * reset, or a switch between the normal and alternate screens. The renderer should then redraw
    * every visible row.
    */
  def generation: Long = _generation

  /**
    * Changes when existing scrollback lines change, rather than only new lines arriving: the
    * scrollback was cleared, a width change rewrapped it, or a resize moved lines back onto the
    * screen. The renderer should then redraw the scrollback.
    */
  def scrollbackVersion: Long = _scrollbackVersion

  /**
    * True when the visible row changed since the last [[clearDirty]]
    */
  def isDirty(row: Int): Boolean = dirty(row)

  /**
    * Mark every visible row as drawn
    */
  def clearDirty(): Unit = java.util.Arrays.fill(dirty, false)

  private[term] def markDirty(row: Int): Unit =
    if row >= 0 && row < _rows then
      dirty(row) = true

  private[term] def markDirty(from: Int, to: Int): Unit =
    var i = math.max(0, from)
    while i <= to && i < _rows do
      dirty(i) = true
      i += 1

  private[term] def markAllDirty(): Unit = java.util.Arrays.fill(dirty, true)

  private def newLine(): Line = Line(_cols, penBg)

  // ---- Printing ----

  private[term] def print(codePoint: Int): Unit =
    val cp =
      if charsets(activeCharset) && codePoint >= 0x5f && codePoint <= 0x7e then
        DecSpecialGraphics(codePoint - 0x5f)
      else
        codePoint
    val width = DisplayWidth.ofCodePoint(cp)
    if width == 0 then
      appendCombining(cp)
    else
      if wrapPending && autoWrap then
        wrap()
      if width == 2 && _cursorCol == _cols - 1 then
        if _cols < 2 then
          return
        if autoWrap then
          lines(_cursorRow).splitAt(_cursorCol, penBg)
          lines(_cursorRow).blank(_cursorCol, penBg)
          markDirty(_cursorRow)
          wrap()
        else
          _cursorCol = _cols - 2
      val line = lines(_cursorRow)
      if insertMode then
        line.insertCells(_cursorCol, width, penBg)
      line.splitAt(_cursorCol, penBg)
      line.splitAt(_cursorCol + width, penBg)
      if width == 2 then
        line.setCell(_cursorCol, cp, penFg, penBg, penFlags | CellFlags.Wide, penLink)
        line.setCell(_cursorCol + 1, 0, penFg, penBg, penFlags | CellFlags.WideTail, penLink)
      else
        line.setCell(_cursorCol, cp, penFg, penBg, penFlags, penLink)
      markDirty(_cursorRow)
      lastPrinted = cp
      _cursorCol += width
      if _cursorCol >= _cols then
        _cursorCol = _cols - 1
        wrapPending = autoWrap
    end if

  end print

  // A zero-width character joins the character before the cursor
  private def appendCombining(cp: Int): Unit =
    var col =
      if wrapPending then
        _cursorCol
      else
        _cursorCol - 1
    val line = lines(_cursorRow)
    if col >= 0 && (line.flags(col) & CellFlags.WideTail) != 0 then
      col -= 1
    if col >= 0 && line.codePoint(col) != 0 then
      line.appendCombining(col, cp)
      markDirty(_cursorRow)

  private def wrap(): Unit =
    wrapPending = false
    _cursorCol = 0
    index()
    lines(_cursorRow).isWrapped = true

  /**
    * Repeat the last printed character (REP)
    */
  private[term] def repeatLast(n: Int): Unit =
    if lastPrinted != 0 then
      var i = 0
      while i < n do
        print(lastPrinted)
        i += 1

  // ---- Cursor movement ----

  private[term] def carriageReturn(): Unit =
    _cursorCol = 0
    wrapPending = false

  private[term] def backspace(): Unit =
    if wrapPending then
      wrapPending = false
    else if _cursorCol > 0 then
      _cursorCol -= 1

  /**
    * Move down one row, scrolling the scroll region at its bottom margin (IND, and LF without
    * newline mode)
    */
  private[term] def index(): Unit =
    wrapPending = false
    if _cursorRow == scrollBottom then
      scrollUp(1)
    else if _cursorRow < _rows - 1 then
      _cursorRow += 1

  private[term] def lineFeed(): Unit =
    index()
    if newLineMode then
      _cursorCol = 0

  /**
    * Move up one row, scrolling the scroll region down at its top margin (RI)
    */
  private[term] def reverseIndex(): Unit =
    wrapPending = false
    if _cursorRow == scrollTop then
      scrollDown(1)
    else if _cursorRow > 0 then
      _cursorRow -= 1

  /**
    * Move to a position. With origin mode on, rows count from the top of the scroll region.
    */
  private[term] def moveTo(row: Int, col: Int): Unit =
    wrapPending = false
    if originMode then
      _cursorRow = clamp(row + scrollTop, scrollTop, scrollBottom)
    else
      _cursorRow = clamp(row, 0, _rows - 1)
    _cursorCol = clamp(col, 0, _cols - 1)

  private[term] def moveToColumn(col: Int): Unit =
    wrapPending = false
    _cursorCol = clamp(col, 0, _cols - 1)

  private[term] def moveToRow(row: Int): Unit = moveTo(row, _cursorCol)

  /**
    * The row moveTo treats as row 0
    */
  private[term] def originRow: Int =
    if originMode then
      scrollTop
    else
      0

  private[term] def cursorUp(n: Int): Unit =
    wrapPending = false
    val top =
      if _cursorRow >= scrollTop then
        scrollTop
      else
        0
    _cursorRow = math.max(top, _cursorRow - n)

  private[term] def cursorDown(n: Int): Unit =
    wrapPending = false
    val bottom =
      if _cursorRow <= scrollBottom then
        scrollBottom
      else
        _rows - 1
    _cursorRow = math.min(bottom, _cursorRow + n)

  private[term] def cursorForward(n: Int): Unit =
    wrapPending = false
    _cursorCol = math.min(_cols - 1, _cursorCol + n)

  private[term] def cursorBackward(n: Int): Unit =
    wrapPending = false
    _cursorCol = math.max(0, _cursorCol - n)

  private[term] def tab(n: Int): Unit =
    var i = 0
    while i < n && _cursorCol < _cols - 1 do
      _cursorCol += 1
      while _cursorCol < _cols - 1 && !tabStops(_cursorCol) do
        _cursorCol += 1
      i += 1

  private[term] def backTab(n: Int): Unit =
    wrapPending = false
    var i = 0
    while i < n && _cursorCol > 0 do
      _cursorCol -= 1
      while _cursorCol > 0 && !tabStops(_cursorCol) do
        _cursorCol -= 1
      i += 1

  private[term] def setTabStop(): Unit = tabStops(_cursorCol) = true

  /**
    * Clear the tab stop at the cursor (mode 0) or all tab stops (mode 3)
    */
  private[term] def clearTabStop(mode: Int): Unit =
    mode match
      case 0 =>
        tabStops(_cursorCol) = false
      case 3 =>
        java.util.Arrays.fill(tabStops, false)
      case _ =>
        ()

  // ---- Scrolling ----

  private[term] def setScrollRegion(top: Int, bottom: Int): Unit =
    val t = clamp(top, 0, _rows - 1)
    val b = clamp(bottom, 0, _rows - 1)
    if t < b then
      scrollTop = t
      scrollBottom = b
      moveTo(0, 0)

  /**
    * Scroll the scroll region up by n lines. Lines leaving the top of a region that starts at the
    * top of the normal screen go to the scrollback.
    */
  private[term] def scrollUp(n: Int): Unit =
    val count = math.min(n, scrollBottom - scrollTop + 1)
    var i     = 0
    while i < count do
      val removed = lines.remove(scrollTop)
      if scrollTop == 0 && !_isAlternate then
        scrollback.push(removed)
        _scrollbackTotal += 1
      lines.insert(scrollBottom, newLine())
      i += 1
    markDirty(scrollTop, scrollBottom)

  private[term] def scrollDown(n: Int): Unit =
    val count = math.min(n, scrollBottom - scrollTop + 1)
    var i     = 0
    while i < count do
      lines.remove(scrollBottom)
      lines.insert(scrollTop, newLine())
      i += 1
    markDirty(scrollTop, scrollBottom)

  private def inScrollRegion: Boolean = _cursorRow >= scrollTop && _cursorRow <= scrollBottom

  private[term] def insertLines(n: Int): Unit =
    if inScrollRegion then
      val count = math.min(n, scrollBottom - _cursorRow + 1)
      var i     = 0
      while i < count do
        lines.remove(scrollBottom)
        lines.insert(_cursorRow, newLine())
        i += 1
      markDirty(_cursorRow, scrollBottom)
      carriageReturn()

  private[term] def deleteLines(n: Int): Unit =
    if inScrollRegion then
      val count = math.min(n, scrollBottom - _cursorRow + 1)
      var i     = 0
      while i < count do
        lines.remove(_cursorRow)
        lines.insert(scrollBottom, newLine())
        i += 1
      markDirty(_cursorRow, scrollBottom)
      carriageReturn()

  // ---- Editing ----

  private[term] def insertChars(n: Int): Unit =
    wrapPending = false
    lines(_cursorRow).insertCells(_cursorCol, math.min(n, _cols - _cursorCol), penBg)
    markDirty(_cursorRow)

  private[term] def deleteChars(n: Int): Unit =
    wrapPending = false
    lines(_cursorRow).deleteCells(_cursorCol, math.min(n, _cols - _cursorCol), penBg)
    markDirty(_cursorRow)

  /**
    * Erase n cells from the cursor without moving the rest (ECH)
    */
  private[term] def eraseChars(n: Int): Unit = eraseCells(
    _cursorRow,
    _cursorCol,
    math.min(_cols, _cursorCol + n)
  )

  private def eraseCells(row: Int, from: Int, until: Int): Unit =
    val line = lines(row)
    line.splitAt(from, penBg)
    line.splitAt(until, penBg)
    line.blank(from, until, penBg)
    markDirty(row)

  private def eraseRow(row: Int): Unit =
    lines(row) = newLine()
    markDirty(row)

  /**
    * Erase in line (EL): 0 from the cursor to the end, 1 from the start to the cursor, 2 the whole
    * line
    */
  private[term] def eraseInLine(mode: Int): Unit =
    mode match
      case 0 =>
        eraseCells(_cursorRow, _cursorCol, _cols)
      case 1 =>
        eraseCells(_cursorRow, 0, _cursorCol + 1)
      case 2 =>
        eraseCells(_cursorRow, 0, _cols)
      case _ =>
        ()

  /**
    * Erase in display (ED): 0 from the cursor to the end, 1 from the start to the cursor, 2 the
    * whole screen, 3 the scrollback
    */
  private[term] def eraseInDisplay(mode: Int): Unit =
    mode match
      case 0 =>
        eraseCells(_cursorRow, _cursorCol, _cols)
        (_cursorRow + 1 until _rows).foreach(eraseRow)
      case 1 =>
        (0 until _cursorRow).foreach(eraseRow)
        eraseCells(_cursorRow, 0, _cursorCol + 1)
      case 2 =>
        (0 until _rows).foreach(eraseRow)
      case 3 =>
        scrollback.clear()
        _scrollbackVersion += 1
      case _ =>
        ()

  /**
    * Fill the screen with `E` (DECALN), used to check alignment
    */
  private[term] def fillWithE(): Unit =
    resetScrollRegion()
    for row <- 0 until _rows do
      val line = newLine()
      for col <- 0 until _cols do
        line.setCell(col, 'E', CellColor.Default, CellColor.Default, 0, 0)
      lines(row) = line
    markAllDirty()
    moveTo(0, 0)

  // ---- Cursor save and screens ----

  private[term] def saveCursor(): Unit =
    val saved = SavedCursor(
      _cursorRow,
      _cursorCol,
      wrapPending,
      penFg,
      penBg,
      penFlags,
      originMode,
      charsets(0),
      charsets(1),
      activeCharset
    )
    if _isAlternate then
      savedAlternate = saved
    else
      savedNormal = saved

  private[term] def restoreCursor(): Unit =
    val s =
      if _isAlternate then
        savedAlternate
      else
        savedNormal
    _cursorRow = clamp(s.row, 0, _rows - 1)
    _cursorCol = clamp(s.col, 0, _cols - 1)
    wrapPending = s.wrapPending
    penFg = s.fg
    penBg = s.bg
    penFlags = s.flags
    originMode = s.originMode
    charsets(0) = s.g0
    charsets(1) = s.g1
    activeCharset = s.activeCharset

  /**
    * Switch to the alternate screen, clearing it first when `clear` is set
    */
  private[term] def enterAlternate(clear: Boolean): Unit =
    if !_isAlternate then
      if clear then
        alternateLines = ArrayBuffer.fill(_rows)(newLine())
      lines = alternateLines
      _isAlternate = true
      _generation += 1
      markAllDirty()

  private[term] def exitAlternate(): Unit =
    if _isAlternate then
      lines = normalLines
      _isAlternate = false
      _generation += 1
      markAllDirty()

  private[term] def resetScrollRegion(): Unit =
    scrollTop = 0
    scrollBottom = _rows - 1

  /**
    * Return to the power-on state (RIS), keeping the size
    */
  private[term] def reset(): Unit =
    exitAlternate()
    normalLines.mapInPlace(_ => Line(_cols, CellColor.Default))
    alternateLines = ArrayBuffer.fill(_rows)(Line(_cols, CellColor.Default))
    scrollback.clear()
    resetPen()
    softReset()
    _cursorRow = 0
    _cursorCol = 0
    reverseVideo = false
    cursorStyle = CursorStyle.Block
    cursorBlink = true
    tabStops = defaultTabStops(_cols)
    savedNormal = SavedCursor()
    savedAlternate = SavedCursor()
    lastPrinted = 0
    _generation += 1
    _scrollbackVersion += 1
    markAllDirty()

  /**
    * Reset modes but keep the screen content (DECSTR)
    */
  private[term] def softReset(): Unit =
    cursorVisible = true
    originMode = false
    autoWrap = true
    insertMode = false
    newLineMode = false
    wrapPending = false
    resetScrollRegion()
    charsets(0) = false
    charsets(1) = false
    activeCharset = 0
    resetPen()

  private[term] def resetPen(): Unit =
    penFg = CellColor.Default
    penBg = CellColor.Default
    penFlags = 0
    penLink = 0

  // ---- Resize ----

  private[term] def resize(newCols: Int, newRows: Int): Unit =
    val c = math.max(1, newCols)
    val r = math.max(1, newRows)
    if c != _cols || r != _rows then
      var pending = false
      if c != _cols then
        pending = reflow(c, r)
        alternateLines.foreach(_.resize(c, CellColor.Default))
        if alternateLines.length > r then
          alternateLines.remove(r, alternateLines.length - r)
        while alternateLines.length < r do
          alternateLines.append(Line(c, CellColor.Default))
        val stops = defaultTabStops(c)
        System.arraycopy(tabStops, 0, stops, 0, math.min(_cols, c))
        tabStops = stops
        _cols = c
      else if r < _rows then
        // Drop empty rows below the cursor first, then push rows off the top into the scrollback
        var excess = _rows - r
        while excess > 0 && normalLines.length - 1 > cursorRowIn(normalLines) &&
          normalLines.last.text.isEmpty
        do
          normalLines.remove(normalLines.length - 1)
          excess -= 1
        while excess > 0 do
          val removed = normalLines.remove(0)
          scrollback.push(removed)
          _scrollbackTotal += 1
          if !_isAlternate then
            _cursorRow -= 1
          savedNormal = savedNormal.copy(row = savedNormal.row - 1)
          excess -= 1
        alternateLines.remove(r, alternateLines.length - r)
      else if r > _rows then
        // Bring lines back from the scrollback, then add empty rows
        var extra = r - _rows
        while extra > 0 && scrollback.size > 0 do
          normalLines.insert(0, scrollback.popNewest())
          _scrollbackVersion += 1
          normalLines.head.resize(_cols, CellColor.Default)
          if !_isAlternate then
            _cursorRow += 1
          savedNormal = savedNormal.copy(row = savedNormal.row + 1)
          extra -= 1
        while normalLines.length < r do
          normalLines.append(Line(_cols, CellColor.Default))
        while alternateLines.length < r do
          alternateLines.append(Line(_cols, CellColor.Default))
      end if
      _rows = r
      lines =
        if _isAlternate then
          alternateLines
        else
          normalLines
      dirty = Array.fill(_rows)(true)
      resetScrollRegion()
      _cursorRow = clamp(_cursorRow, 0, _rows - 1)
      _cursorCol = clamp(_cursorCol, 0, _cols - 1)
      wrapPending = pending
      _generation += 1
    end if

  end resize

  /**
    * Rewrap the normal screen and its scrollback at `newCols` columns, so text that wrapped at the
    * old width wraps at the new one instead of being cut off, and leave `newRows` rows on the
    * screen. The cursor and the saved cursor move with the text they were on.
    *
    * Returns true when the cursor ends just past the last column, where the next character wraps.
    */
  private def reflow(newCols: Int, newRows: Int): Boolean =
    val old = ArrayBuffer.empty[Line]
    var i   = 0
    while i < scrollback.size do
      old += scrollback(i)
      i += 1
    val base = old.size
    old ++= normalLines

    // Positions to carry over: the cursor (when the normal screen shows) and the saved cursor
    val cursorRow =
      if _isAlternate then
        -1
      else
        base + _cursorRow
    // A pending wrap means the cursor is past the last character
    val cursorCol =
      if !_isAlternate && wrapPending then
        _cursorCol + 1
      else
        _cursorCol
    val trackRow   = Array(cursorRow, base + savedNormal.row)
    val trackCol   = Array(cursorCol, savedNormal.col)
    val newRow     = Array(-1, -1)
    val newCol     = Array(0, 0)
    val newPending = Array(false, false)

    // Empty rows below the content and the cursors are dropped; the screen is padded again below
    var end = old.length
    while end > 0 && end - 1 > math.max(trackRow(0), trackRow(1)) && old(end - 1).contentLength == 0
    do
      end -= 1

    val out = ArrayBuffer.empty[Line]

    // Place a tracked position at a column of the last output row, wrapping past the margin
    def place(k: Int, col: Int): Unit =
      newRow(k) = out.size - 1
      if col >= newCols then
        newCol(k) = newCols - 1
        newPending(k) = true
      else
        newCol(k) = col

    var row = 0
    while row < end do
      // A logical line: this row and the rows that continue it
      var last = row
      while last + 1 < end && old(last + 1).isWrapped do
        last += 1
      val first = old(row)
      if row == last && first.contentLength <= newCols then
        // Fits as it is: keep the line, and its trailing colored cells
        first.resize(newCols, CellColor.Default)
        first.isWrapped = false
        out += first
        for k <- 0 until 2 do
          if trackRow(k) == row then
            place(k, trackCol(k))
      else
        var cur = Line(newCols, CellColor.Default)
        out += cur
        var col = 0
        var r   = row
        while r <= last do
          val src = old(r)
          var len =
            if r == last then
              src.contentLength
            else
              src.length
          // A blank the terminal left at the margin because a wide character did not fit
          if r < last && len > 0 && src.codePoint(len - 1) == 0 &&
            (old(r + 1).flags(0) & CellFlags.Wide) != 0
          then
            len -= 1
          var c = 0
          while c < len do
            val flags = src.flags(c)
            if (flags & CellFlags.WideTail) == 0 then
              val width =
                if (flags & CellFlags.Wide) != 0 then
                  2
                else
                  1
              if col + width > newCols then
                cur = Line(newCols, CellColor.Default)
                cur.isWrapped = true
                out += cur
                col = 0
              for k <- 0 until 2 do
                if trackRow(k) == r && (trackCol(k) == c || (width == 2 && trackCol(k) == c + 1))
                then
                  place(k, col)
              if width == 2 && newCols < 2 then
                // A wide character cannot show in one column
                ()
              else
                src.copyCellTo(c, cur, col)
                if width == 2 then
                  src.copyCellTo(c + 1, cur, col + 1)
              col += width
            c += 1
          end while
          // A position past the copied cells of this row
          for k <- 0 until 2 do
            if trackRow(k) == r && trackCol(k) >= len then
              place(k, col + trackCol(k) - len)
          r += 1
        end while
      end if
      row = last + 1
    end while

    if out.isEmpty then
      out += Line(newCols, CellColor.Default)
      newRow(0) = 0
      newRow(1) = 0

    // Show the last rows, but never scroll the row the cursor is on off the screen
    val anchor =
      if _isAlternate then
        newRow(1)
      else
        newRow(0)
    val top = clamp(out.size - newRows, 0, math.max(anchor, 0))

    scrollback.clear()
    var j = 0
    while j < top do
      scrollback.push(out(j))
      j += 1
    _scrollbackTotal += math.max(0, top - base)
    _scrollbackVersion += 1

    normalLines.clear()
    normalLines ++= out.slice(top, math.min(out.size, top + newRows))
    while normalLines.length < newRows do
      normalLines.append(Line(newCols, CellColor.Default))

    savedNormal = savedNormal.copy(row = math.max(0, newRow(1) - top), col = newCol(1))
    if _isAlternate then
      false
    else
      _cursorRow = newRow(0) - top
      _cursorCol = newCol(0)
      newPending(0)

  end reflow

  private def cursorRowIn(buf: ArrayBuffer[Line]): Int =
    if buf eq lines then
      _cursorRow
    else
      -1

end Screen

/**
  * The cursor shape a program sets with DECSCUSR (`CSI n SP q`)
  */
enum CursorStyle:
  case Block,
    Underline,
    Bar

object Screen:
  private case class SavedCursor(
      row: Int = 0,
      col: Int = 0,
      wrapPending: Boolean = false,
      fg: Int = CellColor.Default,
      bg: Int = CellColor.Default,
      flags: Int = 0,
      originMode: Boolean = false,
      g0: Boolean = false,
      g1: Boolean = false,
      activeCharset: Int = 0
  )

  private def clamp(v: Int, lo: Int, hi: Int): Int = math.max(lo, math.min(hi, v))

  private def defaultTabStops(cols: Int): Array[Boolean] =
    Array.tabulate(cols)(i => i > 0 && i % 8 == 0)

  // DEC special graphics for 0x5f-0x7e: the line-drawing characters used by curses programs
  private val DecSpecialGraphics: Array[Int] =
    " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·".map(_.toInt).toArray

end Screen
