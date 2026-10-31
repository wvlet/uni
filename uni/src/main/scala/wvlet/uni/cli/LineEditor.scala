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
package wvlet.uni.cli

/**
  * The state of one [[LineReader.readLine]] call: the input buffer, the cursor, and what is drawn
  * on the terminal. [[LineAction.Custom]] actions receive it to read and change the input.
  *
  * The input can span several lines; continuation lines are drawn after
  * [[LineReaderConfig.continuationPrompt]]. Each redraw moves the cursor back to the first row of
  * the prompt, clears to the end of the screen, and draws everything again, so long lines that wrap
  * and wide characters are handled in one place.
  */
class LineEditor private[cli] (
    prompt: String,
    config: LineReaderConfig,
    history: LineHistory,
    io: TerminalIO
):
  import LineEditor.*

  private val interactive  = io.isTTY
  private val text         = StringBuilder()
  private var pos          = 0
  private var killed       = ""
  private var pendingKeys  = Vector.empty[Key]
  private var resultOpt    = Option.empty[LineResult]
  private var historyIndex = history.size
  // The input being edited before browsing history, restored when coming back down
  private var draft  = ""
  private var search = Option.empty[Search]
  // Rows between the first row of the prompt and the cursor, from the last redraw
  private var cursorRow = 0

  private case class Search(query: String, index: Int, failing: Boolean, original: String)

  /**
    * The current input
    */
  def buffer: String = text.toString

  /**
    * The cursor position in [[buffer]]
    */
  def cursor: Int = pos

  def result: Option[LineResult] = resultOpt
  def isDone: Boolean            = resultOpt.isDefined

  /**
    * Replace the input and move the cursor, by default to the end
    */
  def setBuffer(newText: String, newCursor: Int = -1): Unit =
    text.clear()
    text.append(newText)
    pos =
      if newCursor < 0 then
        newText.length
      else
        newCursor.min(newText.length)

  /**
    * Insert text at the cursor
    */
  def insert(s: String): Unit =
    text.insert(pos, s)
    pos += s.length

  def moveCursor(newCursor: Int): Unit = pos = newCursor.max(0).min(text.length)

  /**
    * Accept the current input as is
    */
  def accept(): Unit = finish(LineResult.Line(buffer))

  /**
    * Run a built-in or custom action
    */
  def run(action: LineAction): Unit =
    action match
      case LineAction.AcceptLine =>
        if !interactive || config.isInputComplete(buffer) then
          accept()
        else
          newline()
      case LineAction.InsertNewline =>
        newline()
      case LineAction.BackwardChar =>
        pos = prevCharPos(pos)
      case LineAction.ForwardChar =>
        pos = nextCharPos(pos)
      case LineAction.BackwardWord =>
        pos = prevWordPos(pos)
      case LineAction.ForwardWord =>
        pos = nextWordPos(pos)
      case LineAction.BeginningOfLine =>
        pos = lineStart(pos)
      case LineAction.EndOfLine =>
        pos = lineEnd(pos)
      case LineAction.BeginningOfBuffer =>
        pos = 0
      case LineAction.EndOfBuffer =>
        pos = text.length
      case LineAction.UpLineOrHistory =>
        if lineStart(pos) == 0 then
          previousHistory()
        else
          moveToLine(lineStart(lineStart(pos) - 1))
      case LineAction.DownLineOrHistory =>
        if lineEnd(pos) == text.length then
          nextHistory()
        else
          moveToLine(lineEnd(pos) + 1)
      case LineAction.PreviousHistory =>
        previousHistory()
      case LineAction.NextHistory =>
        nextHistory()
      case LineAction.ReverseSearchHistory =>
        search = Some(Search("", -1, false, buffer))
      case LineAction.BackwardDeleteChar =>
        val p = prevCharPos(pos)
        text.delete(p, pos)
        pos = p
      case LineAction.DeleteChar =>
        text.delete(pos, nextCharPos(pos))
      case LineAction.DeleteCharOrEndOfInput =>
        if text.isEmpty then
          finish(LineResult.EndOfInput)
        else
          run(LineAction.DeleteChar)
      case LineAction.KillLine =>
        val end = lineEnd(pos)
        // At the end of a line, join it with the next one
        kill(
          pos,
          if end == pos && end < text.length then
            end + 1
          else
            end
        )
      case LineAction.BackwardKillLine =>
        kill(lineStart(pos), pos)
      case LineAction.KillWord =>
        kill(pos, nextWordPos(pos))
      case LineAction.BackwardKillWord =>
        kill(prevWordPos(pos), pos)
      case LineAction.Yank =>
        insert(killed)
      case LineAction.Complete =>
        complete()
      case LineAction.ClearScreen =>
        if interactive then
          io.write(s"${CSI}2J${CSI}H")
          cursorRow = 0
      case LineAction.Interrupt =>
        finish(LineResult.Interrupted(buffer))
      case LineAction.Custom(_, f) =>
        f(this)

  /**
    * Print text above the input, which is then drawn again below it
    */
  def printAbove(s: String): Unit =
    if interactive && !isDone then
      val out = StringBuilder()
      clearDrawing(out)
      out.append(s.replace("\r\n", "\n").replace("\n", "\r\n"))
      if !s.endsWith("\n") then
        out.append("\r\n")
      io.write(out.toString)
      cursorRow = 0
      render()
    else
      io.write(
        if s.endsWith("\n") then
          s
        else
          s"${s}\n"
      )
      io.flush()

  private[cli] def handle(key: Key): Unit =
    if !isDone then
      search match
        case Some(s) =>
          handleSearch(s, key)
        case None =>
          handleKey(key)

  /**
    * Input ended (Ctrl-D in cooked mode, a closed pipe): return what was typed, or EndOfInput
    */
  private[cli] def endOfInput(): Unit =
    if !isDone then
      if text.isEmpty then
        finish(LineResult.EndOfInput)
      else
        accept()

  private def handleKey(key: Key): Unit =
    pendingKeys :+= key
    val keys = pendingKeys
    if !config.keyMap.isPrefix(keys) then
      pendingKeys = Vector.empty
      config.keyMap.get(keys) match
        case Some(action) =>
          run(action)
        case None if keys.size == 1 =>
          insertKey(key)
        case None =>
          // The keys started a chord but did not finish it: run the first one alone, then replay
          // the rest
          config.keyMap.get(Seq(keys.head)).fold(insertKey(keys.head))(run)
          keys
            .tail
            .foreach(k =>
              if !isDone then
                handleKey(k)
            )

  private def insertKey(key: Key): Unit =
    key match
      case Key.Char(cp) =>
        insert(String(Character.toChars(cp)))
      case Key.Paste(s) =>
        insert(s)
      case _ =>
      // Ignore unbound keys

  private def handleSearch(s: Search, key: Key): Unit =
    key match
      case Key.Char(cp) =>
        val query = s"${s.query}${String(Character.toChars(cp))}"
        val from  =
          if s.index >= 0 then
            s.index
          else
            history.size - 1
        updateSearch(s, query, from)
      case Key.Backspace =>
        val query = s.query.dropRight(1)
        updateSearch(s, query, history.size - 1)
      case Key.Ctrl('r') =>
        val from =
          if s.index >= 0 then
            s.index - 1
          else
            history.size - 1
        updateSearch(s, s.query, from)
      case Key.Ctrl('g') | Key.Escape =>
        search = None
        setBuffer(s.original)
      case Key.Enter =>
        search = None
        run(LineAction.AcceptLine)
      case other =>
        // Any other key keeps the match and then does what it normally does
        search = None
        handleKey(other)

  private def updateSearch(s: Search, query: String, from: Int): Unit =
    val found =
      if query.isEmpty then
        -1
      else
        (from to 0 by -1).find(i => history(i).contains(query)).getOrElse(-1)
    if found >= 0 then
      val entry = history(found)
      setBuffer(entry, entry.lastIndexOf(query))
      search = Some(s.copy(query = query, index = found, failing = false))
    else
      search = Some(s.copy(query = query, failing = query.nonEmpty))

  private def finish(r: LineResult): Unit =
    if !isDone then
      search = None
      if interactive then
        r match
          case LineResult.Line(_) =>
            pos = text.length
          case _ =>
        render()
        io.write("\r\n")
        io.flush()
      resultOpt = Some(r)
      r match
        case LineResult.Line(line) =>
          history.add(line)
        case _ =>

  private def newline(): Unit =
    val current = buffer.substring(lineStart(pos), pos)
    val indent  =
      if interactive then
        current.takeWhile(c => c == ' ' || c == '\t')
      else
        ""
    insert(s"\n${indent}")

  private def kill(from: Int, to: Int): Unit =
    if from < to then
      killed = text.substring(from, to)
      text.delete(from, to)
      pos = from

  private def previousHistory(): Unit =
    if historyIndex > 0 then
      if historyIndex == history.size then
        draft = buffer
      historyIndex -= 1
      setBuffer(history(historyIndex))

  private def nextHistory(): Unit =
    if historyIndex < history.size then
      historyIndex += 1
      setBuffer(
        if historyIndex == history.size then
          draft
        else
          history(historyIndex)
      )

  private def moveToLine(start: Int): Unit =
    val column = pos - lineStart(pos)
    pos = (start + column).min(lineEnd(start))

  private def complete(): Unit =
    val completion               = config.completer.complete(buffer, pos)
    val candidates               = completion.candidates
    val start                    = completion.start.max(0).min(pos)
    def replace(s: String): Unit =
      text.replace(start, pos, s)
      pos = start + s.length
    if candidates.size == 1 then
      val c = candidates.head
      replace(
        if c.complete then
          s"${c.value} "
        else
          c.value
      )
    else if candidates.nonEmpty then
      val prefix = commonPrefix(candidates.map(_.value))
      if prefix.length > pos - start then
        replace(prefix)
      else
        printAbove(formatColumns(candidates.map(_.displayText), io.size.columns))

  private def lineStart(p: Int): Int = text.lastIndexOf("\n", p - 1) + 1

  private def lineEnd(p: Int): Int =
    val i = text.indexOf("\n", p)
    if i < 0 then
      text.length
    else
      i

  private def prevCharPos(p: Int): Int =
    if p <= 0 then
      0
    else if p >= 2 && Character.isLowSurrogate(text.charAt(p - 1)) &&
      Character.isHighSurrogate(text.charAt(p - 2))
    then
      p - 2
    else
      p - 1

  private def nextCharPos(p: Int): Int =
    if p >= text.length then
      text.length
    else
      p + Character.charCount(text.underlying.codePointAt(p))

  private def isWordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_'

  private def prevWordPos(p: Int): Int =
    var i = p
    while i > 0 && !isWordChar(text.charAt(i - 1)) do
      i -= 1
    while i > 0 && isWordChar(text.charAt(i - 1)) do
      i -= 1
    i

  private def nextWordPos(p: Int): Int =
    var i = p
    while i < text.length && !isWordChar(text.charAt(i)) do
      i += 1
    while i < text.length && isWordChar(text.charAt(i)) do
      i += 1
    i

  /**
    * Move the cursor from where it is to the first row of the prompt and clear what follows
    */
  private def clearDrawing(out: StringBuilder): Unit =
    if cursorRow > 0 then
      out.append(s"${CSI}${cursorRow}A")
    out.append(s"\r${CSI}0J")

  /**
    * Draw the prompt and the input, and place the cursor
    */
  private[cli] def render(): Unit =
    if interactive then
      val width                                 = io.size.columns.max(1)
      val (firstPrompt, shownText, shownCursor) =
        search match
          case Some(s) =>
            val failing =
              if s.failing then
                "failing "
              else
                ""
            (s"(${failing}reverse-i-search)`${s.query}': ", buffer, pos)
          case None =>
            (prompt, buffer, pos)
      val lines       = shownText.split("\n", -1)
      val highlighted =
        val h = config.highlighter(shownText).split("\n", -1)
        if h.length == lines.length then
          h
        else
          lines
      val contPrompt =
        if config.continuationPrompt.nonEmpty then
          config.continuationPrompt
        else
          " " * DisplayWidth.of(firstPrompt)
      def promptOf(i: Int): String =
        if i == 0 then
          firstPrompt
        else
          contPrompt

      val out = StringBuilder()
      clearDrawing(out)
      out.append(HideCursor)

      // The row where each line starts, counted from the first row of the prompt
      var row       = 0
      var lineIndex = 0
      // The cursor's line and its offset in that line
      val cursorLine   = shownText.substring(0, shownCursor).count(_ == '\n')
      val cursorOffset = shownCursor - (shownText.lastIndexOf('\n', shownCursor - 1) + 1)
      var curRow       = 0
      var curCol       = 0
      var endRow       = 0
      var endCol       = 0
      while lineIndex < lines.length do
        val p = promptOf(lineIndex)
        out.append(p)
        out.append(expandTabs(highlighted(lineIndex)))
        val w = DisplayWidth.of(p) + DisplayWidth.of(expandTabs(lines(lineIndex)))
        if lineIndex == cursorLine then
          val cw =
            DisplayWidth.of(p) +
              DisplayWidth.of(expandTabs(lines(lineIndex).substring(0, cursorOffset)))
          curRow = row + cw / width
          curCol = cw % width
        if lineIndex < lines.length - 1 then
          out.append("\r\n")
          row += rowsOf(w, width)
        else
          endRow = row + w / width
          endCol = w % width
          if w > 0 && endCol == 0 then
            // The text filled the last row exactly; the terminal holds the cursor at the right
            // edge until the next character, so move it down explicitly
            out.append("\r\n")
        lineIndex += 1
      end while

      if endRow > curRow then
        out.append(s"${CSI}${endRow - curRow}A")
      out.append("\r")
      if curCol > 0 then
        out.append(s"${CSI}${curCol}C")
      out.append(ShowCursor)
      io.write(out.toString)
      io.flush()
      cursorRow = curRow

end LineEditor

object LineEditor:
  private val CSI        = "\u001b["
  private val HideCursor = s"${CSI}?25l"
  private val ShowCursor = s"${CSI}?25h"

  private def expandTabs(s: String): String = s.replace("\t", "    ")

  private def rowsOf(width: Int, columns: Int): Int =
    if width == 0 then
      1
    else
      (width + columns - 1) / columns

  private[cli] def commonPrefix(values: Seq[String]): String =
    if values.isEmpty then
      ""
    else
      values.reduce { (a, b) =>
        var i = 0
        while i < a.length && i < b.length && a.charAt(i) == b.charAt(i) do
          i += 1
        a.substring(0, i)
      }

  /**
    * Lay out completion candidates in columns, ordered top to bottom
    */
  private[cli] def formatColumns(items: Seq[String], columns: Int): String =
    val cellWidth = items.map(DisplayWidth.of).max + 2
    val perRow    = (columns / cellWidth).max(1)
    val rows      = (items.size + perRow - 1) / perRow
    (0 until rows)
      .map { r =>
        (0 until perRow)
          .flatMap(c => items.lift(c * rows + r))
          .map(s => s + " " * (cellWidth - DisplayWidth.of(s)))
          .mkString
          .stripTrailing()
      }
      .mkString("\n")

end LineEditor
