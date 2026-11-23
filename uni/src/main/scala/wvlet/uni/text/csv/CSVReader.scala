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

import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.json.JSON.JSONString
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.text.Span
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.codec.CaseClassWeaver

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/**
  * A stream of CSV rows that closes its source at the end of input or on an error. Close it
  * yourself when you stop reading early.
  */
final class CSVReader[A] private[csv] (underlying: Iterator[A], onClose: () => Unit)
    extends Iterator[A]
    with AutoCloseable:
  private var closed = false

  override def hasNext: Boolean =
    if closed then
      false
    else
      val has =
        try
          underlying.hasNext
        catch
          case e: Throwable =>
            close()
            throw e
      if !has then
        close()
      has

  override def next(): A =
    if closed then
      throw java.util.NoSuchElementException("The CSV reader is closed")
    try
      underlying.next()
    catch
      case e: Throwable =>
        close()
        throw e

  override def close(): Unit =
    if !closed then
      closed = true
      onClose()

end CSVReader

object CSVReader:

  /**
    * Decode UTF-8 byte chunks into text chunks. A character split across two chunks is carried to
    * the next one. Closing the result closes the byte source when it is closeable.
    */
  def decodeUTF8(chunks: Iterator[Array[Byte]]): Iterator[String] & AutoCloseable = UTF8Chunks(
    chunks
  )

  private[csv] def closeSource(source: Iterator[?]): Unit =
    source match
      case c: AutoCloseable =>
        c.close()
      case _ =>

end CSVReader

private[csv] final class UTF8Chunks(chunks: Iterator[Array[Byte]])
    extends Iterator[String]
    with AutoCloseable:
  private var carry: Array[Byte] = Array.emptyByteArray

  override def hasNext: Boolean = chunks.hasNext || carry.nonEmpty

  override def next(): String =
    if !chunks.hasNext then
      // Malformed trailing bytes decode as the replacement character
      val rest = carry
      carry = Array.emptyByteArray
      String(rest, StandardCharsets.UTF_8)
    else
      val chunk =
        val c = chunks.next()
        if carry.isEmpty then
          c
        else
          val joined = new Array[Byte](carry.length + c.length)
          System.arraycopy(carry, 0, joined, 0, carry.length)
          System.arraycopy(c, 0, joined, carry.length, c.length)
          joined
      val end = UTF8Chunks.completeLength(chunk)
      carry = java.util.Arrays.copyOfRange(chunk, end, chunk.length)
      String(chunk, 0, end, StandardCharsets.UTF_8)

  override def close(): Unit = CSVReader.closeSource(chunks)

end UTF8Chunks

private[csv] object UTF8Chunks:
  /**
    * The length of the prefix that ends on a character boundary: an incomplete multi-byte sequence
    * at the end (at most 3 bytes) is left for the next chunk
    */
  def completeLength(b: Array[Byte]): Int =
    val n = b.length
    var i = n - 1
    // Step back over continuation bytes (10xxxxxx), at most 3
    while i >= 0 && i >= n - 3 && (b(i) & 0xc0) == 0x80 do
      i -= 1
    if i < 0 then
      n
    else
      val lead = b(i) & 0xff
      val len  =
        if lead < 0x80 then
          1
        else if (lead & 0xe0) == 0xc0 then
          2
        else if (lead & 0xf0) == 0xe0 then
          3
        else if (lead & 0xf8) == 0xf0 then
          4
        else
          1
      if i + len > n then
        i
      else
        n

end UTF8Chunks

/**
  * Turns records into values of `A`: names the fields from the columns, the header line, or the
  * case class, and drops missing values so fields fall back to their defaults or `None`
  */
private[csv] final class CSVDecoder[A](format: CSVFormat, parser: CSVParser, weaver: Weaver[A])
    extends Iterator[A]:
  private var names: IndexedSeq[String] | Null = null
  private var nextRecord: CSVRecord | Null     = null
  private var fetched                          = false

  private def fetch(): Unit =
    if !fetched then
      fetched = true
      if names == null then
        names = resolveNames()
      nextRecord = parser.nextRecord()

  private def resolveNames(): IndexedSeq[String] =
    val header =
      if format.hasHeader then
        parser.nextRecord() match
          case null =>
            null
          case h: CSVRecord =>
            checkHeader(h)
            h.values
      else
        null
    if format.columns.nonEmpty then
      format.columns.toIndexedSeq
    else if header != null then
      header
    else
      weaver match
        case c: CaseClassWeaver[?] =>
          c.fieldNames
        case _ =>
          if format.hasHeader then
            IndexedSeq.empty // empty input
          else
            throw IllegalArgumentException(
              "Name the columns with withColumns to decode a CSV file without a header"
            )

  private def checkHeader(h: CSVRecord): Unit =
    val seen = scala.collection.mutable.Set.empty[String]
    h.values
      .zipWithIndex
      .foreach { (name, i) =>
        if name.isEmpty then
          fail(s"The header has an empty name for column ${i + 1}", h.line)
        if !seen.add(name) then
          fail(s"The header has a duplicate column name '${name}'", h.line)
      }

  private def fail(message: String, line: Int, cause: Throwable = null): Nothing =
    throw TextParseException(s"${message} (line ${line})", 0, Span.NoSpan, cause)

  override def hasNext: Boolean =
    fetch()
    nextRecord != null

  override def next(): A =
    fetch()
    nextRecord match
      case null =>
        throw java.util.NoSuchElementException("No more CSV rows")
      case r: CSVRecord =>
        fetched = false
        nextRecord = null
        decode(r)

  private def decode(r: CSVRecord): A =
    val cols    = names.nn
    val n       = cols.size.min(r.size)
    val entries = Seq.newBuilder[(String, JSONValue)]
    var i       = 0
    while i < n do
      if !r.nulls(i) then
        entries += cols(i) -> JSONString(r.values(i))
      i += 1
    try
      weaver.fromJSONValue(JSONObject(entries.result()))
    catch
      case NonFatal(e) =>
        fail(s"Can't decode the row: ${e.getMessage}", r.line, e)

end CSVDecoder
