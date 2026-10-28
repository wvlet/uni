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
package wvlet.uni.text.parser

import wvlet.uni.text.Span

/**
  * A character cursor for line- and indentation-oriented formats (YAML, Markdown), where block
  * structure depends on columns and line prefixes rather than on tokens. Expression-like languages
  * should use [[ScannerBase]] instead.
  *
  * Line breaks (`\r\n`, `\r`) are normalized to `\n`, so offsets and spans refer to [[text]].
  */
class LineReader(input: String):
  import LineReader.*

  /**
    * The input text with normalized line breaks
    */
  val text: String = normalizeLineBreaks(input)

  private var pos: Int       = 0
  private var lineNum: Int   = 0
  private var lineStart: Int = 0

  def offset: Int = pos

  /**
    * The current 0-origin line number
    */
  def line: Int = lineNum

  /**
    * The current 0-origin column
    */
  def column: Int = pos - lineStart

  def isEOF: Boolean = pos >= text.length

  /**
    * The current character, or [[Tokens.SU]] at the end of the input
    */
  def peek: Char = charAt(pos)

  /**
    * The character `n` positions ahead of the current one, or [[Tokens.SU]] past the end
    */
  def lookAhead(n: Int): Char = charAt(pos + n)

  private def charAt(i: Int): Char =
    if i < text.length then
      text.charAt(i)
    else
      Tokens.SU

  def isAtLineStart: Boolean = pos == lineStart
  def isAtLineEnd: Boolean   = isEOF || peek == '\n'

  def startsWith(prefix: String): Boolean = text.startsWith(prefix, pos)

  def advance(): Unit =
    if !isEOF then
      if text.charAt(pos) == '\n' then
        lineNum += 1
        lineStart = pos + 1
      pos += 1

  def advance(n: Int): Unit =
    var i = 0
    while i < n do
      advance()
      i += 1

  /**
    * Skip space characters (not tabs) and return the number of skipped characters
    */
  def skipSpaces(): Int =
    val start = pos
    while peek == ' ' do
      pos += 1
    pos - start

  /**
    * Skip spaces and tabs and return the number of skipped characters
    */
  def skipWhiteSpaces(): Int =
    val start = pos
    while peek == ' ' || peek == '\t' do
      pos += 1
    pos - start

  /**
    * The text from the current position to the end of the line, without consuming it
    */
  def restOfLine: String = text.substring(pos, lineEndOffset)

  /**
    * Consume and return the text up to (not including) the next line break
    */
  def readToLineEnd(): String =
    val end = lineEndOffset
    val s   = text.substring(pos, end)
    pos = end
    s

  /**
    * Consume a line break if the current character is one
    */
  def skipLineBreak(): Boolean =
    if peek == '\n' then
      advance()
      true
    else
      false

  /**
    * Move to the beginning of the next line, discarding the rest of the current line
    */
  def nextLine(): Unit =
    readToLineEnd()
    skipLineBreak()

  private def lineEndOffset: Int =
    val i = text.indexOf('\n', pos)
    if i < 0 then
      text.length
    else
      i

  /**
    * Save the current position to [[reset]] to it later
    */
  def mark: Mark = Mark(pos, lineNum, lineStart)

  def reset(m: Mark): Unit =
    pos = m.offset
    lineNum = m.line
    lineStart = m.lineStart

  def spanFrom(start: Int): Span = Span.within(start, pos)

  /**
    * Throw a [[TextParseException]] whose message shows the line, column, and the source line with
    * a caret under the error position.
    */
  def error(message: String, at: Int = pos): Nothing =
    throw TextParseException(formatError(text, message, at), at, Span.at(at))

end LineReader

object LineReader:
  case class Mark(offset: Int, line: Int, lineStart: Int)

  def normalizeLineBreaks(s: String): String =
    if s.indexOf('\r') < 0 then
      s
    else
      s.replace("\r\n", "\n").replace('\r', '\n')

  /**
    * Return the 0-origin (line, column) of the offset in the text
    */
  def lineColumn(text: String, offset: Int): (Int, Int) =
    val end       = offset.min(text.length)
    var line      = 0
    var lineStart = 0
    var i         = 0
    while i < end do
      if text.charAt(i) == '\n' then
        line += 1
        lineStart = i + 1
      i += 1
    (line, end - lineStart)

  def formatError(text: String, message: String, offset: Int): String =
    val (line, col) = lineColumn(text, offset)
    val lineStart   = offset.min(text.length) - col
    val lineEnd     =
      val i = text.indexOf('\n', lineStart)
      if i < 0 then
        text.length
      else
        i
    val lineText = text.substring(lineStart, lineEnd)
    s"${message} (line ${line + 1}, column ${col + 1})\n${lineText}\n${" " * col}^"

end LineReader
