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
package wvlet.uni.text.csv

import wvlet.uni.text.Span
import wvlet.uni.text.parser.TextParseException

import scala.collection.mutable.ArrayBuffer

/**
  * One parsed line of a CSV file
  *
  * @param values
  *   the field texts, with escapes decoded
  * @param nulls
  *   whether each field matched the null string
  * @param line
  *   the 1-origin line where the record starts
  */
private[csv] final class CSVRecord(
    val values: IndexedSeq[String],
    val nulls: IndexedSeq[Boolean],
    val line: Int
):
  def size: Int = values.size

  /**
    * The values with missing ones as empty strings
    */
  def valuesOrEmpty: IndexedSeq[String] =
    if !nulls.contains(true) then
      values
    else
      values
        .indices
        .map { i =>
          if nulls(i) then
            ""
          else
            values(i)
        }

/**
  * A pull parser over text chunks. It reads one character at a time from the current chunk and
  * pulls the next chunk when it runs out, so fields, quotes, and `\r\n` may span chunks.
  */
private[csv] final class CSVParser(format: CSVFormat, chunks: Iterator[String]):
  private val delimiter = format.delimiter
  private val quote     = format.quote
  private val style     = format.escapeStyle

  private var chunk: String = ""
  private var idx: Int      = 0
  private var offset: Long  = 0L
  private var line: Int     = 1
  private var column: Int   = 1
  private var started       = false

  // The field count every record must have, set by the first record (or the columns)
  private var expectedFields: Int =
    if format.columns.nonEmpty && !format.hasHeader then
      format.columns.size
    else
      -1

  private val value  = StringBuilder()
  private val raw    = StringBuilder()
  private val values = ArrayBuffer.empty[String]
  private val nulls  = ArrayBuffer.empty[Boolean]

  // Whether the last field read any characters, to tell a blank line from an empty field
  private var fieldHadContent = false

  import CSVParser.Delim
  import CSVParser.EOL
  import CSVParser.EOF

  private def fill(): Boolean =
    while idx >= chunk.length && chunks.hasNext do
      chunk = chunks.next()
      idx = 0
    idx < chunk.length

  private def peek: Int =
    if fill() then
      chunk.charAt(idx)
    else
      -1

  private def advance(): Int =
    if !fill() then
      -1
    else
      val c = chunk.charAt(idx)
      idx += 1
      offset += 1
      if c == '\n' then
        line += 1
        column = 1
      else if c == '\r' && peek != '\n' then
        line += 1
        column = 1
      else
        column += 1
      c

  private def error(message: String, atLine: Int, atColumn: Int, atOffset: Long): Nothing =
    val off = atOffset.min(Int.MaxValue.toLong).toInt
    throw TextParseException(s"${message} (line ${atLine}, column ${atColumn})", off, Span.at(off))

  private def error(message: String): Nothing = error(message, line, column, offset)

  /**
    * Read the next record, or null at the end of input. Blank lines are skipped.
    */
  def nextRecord(): CSVRecord | Null =
    if !started then
      started = true
      if peek == '﻿' then
        // Skip the byte order mark without counting it as a column
        idx += 1
        offset += 1

    var result: CSVRecord | Null = null
    var done                     = false
    while !done do
      if peek == -1 then
        done = true
      else
        val startLine = line
        values.clear()
        nulls.clear()
        var term = Delim
        while term == Delim do
          term = readField()
        val blank = values.size == 1 && !fieldHadContent
        if !blank then
          checkFieldCount(startLine)
          result = CSVRecord(values.toIndexedSeq, nulls.toIndexedSeq, startLine)
          done = true
    result

  end nextRecord

  private def checkFieldCount(startLine: Int): Unit =
    if expectedFields < 0 then
      expectedFields = values.size
    else if values.size != expectedFields && !format.raggedRows then
      error(
        s"Expected ${expectedFields} fields, but found ${values
            .size}. Use withRaggedRows to accept rows of different lengths",
        startLine,
        1,
        offset
      )

  private def endField(isNull: Boolean): Unit =
    values += value.toString
    nulls += isNull
    value.clear()

  private def matchesNull(text: StringBuilder): Boolean =
    format.nullString match
      case Some(n) =>
        text.length == n.length && text.toString == n
      case None =>
        false

  private def consumeLineBreak(): Unit =
    val c = advance()
    if c == '\r' && peek == '\n' then
      advance()

  /**
    * Read a field and its terminator
    */
  private def readField(): Int =
    style match
      case CSVEscapeStyle.Quoted if peek == quote =>
        readQuotedField()
      case CSVEscapeStyle.Backslash =>
        readBackslashField()
      case _ =>
        readUnquotedField()

  private def terminator(c: Int): Int =
    if c == delimiter then
      advance()
      Delim
    else if c == '\n' || c == '\r' then
      consumeLineBreak()
      EOL
    else
      EOF

  private def isTerminator(c: Int): Boolean = c == delimiter || c == '\n' || c == '\r' || c == -1

  private def readUnquotedField(): Int =
    val checkQuote = style == CSVEscapeStyle.Quoted
    fieldHadContent = false
    var c = peek
    while !isTerminator(c) do
      if checkQuote && c == quote then
        error(
          s"A quote (${quote}) inside an unquoted field. Quote the whole field and double the quotes inside it"
        )
      value += c.toChar
      fieldHadContent = true
      advance()
      c = peek
    endField(matchesNull(value))
    terminator(c)

  private def readQuotedField(): Int =
    val qLine   = line
    val qColumn = column
    val qOffset = offset
    fieldHadContent = true
    advance()
    var closed = false
    while !closed do
      val c = advance()
      if c == -1 then
        error("Unclosed quote", qLine, qColumn, qOffset)
      else if c == quote then
        if peek == quote then
          advance()
          value += quote
        else
          closed = true
      else
        value += c.toChar
    val next = peek
    if !isTerminator(next) then
      error("Expected a delimiter or a line break after the closing quote")
    endField(false)
    terminator(next)

  private def readBackslashField(): Int =
    raw.clear()
    var c = peek
    while !isTerminator(c) do
      advance()
      raw += c.toChar
      if c == '\\' then
        val e = advance()
        if e == -1 then
          error("A backslash at the end of input")
        raw += e.toChar
        value += CSVParser.unescape(e.toChar)
      else
        value += c.toChar
      c = peek
    fieldHadContent = raw.nonEmpty
    endField(matchesNull(raw))
    terminator(c)

end CSVParser

private[csv] object CSVParser:
  // Field terminators
  final val Delim = 0
  final val EOL   = 1
  final val EOF   = 2

  def unescape(c: Char): Char =
    c match
      case 'n' =>
        '\n'
      case 't' =>
        '\t'
      case 'r' =>
        '\r'
      case 'b' =>
        '\b'
      case 'f' =>
        '\f'
      case 'v' =>
        '\u000b'
      case other =>
        other
