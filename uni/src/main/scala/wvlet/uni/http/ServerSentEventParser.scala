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
package wvlet.uni.http

import scala.collection.mutable.ArrayBuffer

/**
  * Parser for Server-Sent Events (SSE) wire format.
  *
  * This parser processes the SSE text stream and emits ServerSentEvent objects. It handles:
  *   - Event fields: `id`, `event`, `data`, `retry`
  *   - Multi-line data (multiple `data:` fields)
  *   - Comments (lines starting with `:`)
  *   - Event boundaries (blank lines)
  *
  * The parser maintains state between calls to `feed()`, allowing incremental parsing of streaming
  * data.
  *
  * SSE Wire Format (RFC 6657):
  * {{{
  * id: <event-id>
  * event: <event-type>
  * retry: <milliseconds>
  * data: <line1>
  * data: <line2>
  *
  * }}}
  *
  * Example usage:
  * {{{
  * val parser = ServerSentEventParser()
  * val events = parser.feed("data: hello\n\ndata: world\n\n")
  * // events contains two ServerSentEvent objects
  * }}}
  *
  * The stream comes from a remote server, so the parser bounds the memory it holds: a line longer
  * than `maxLineLength`, or an event whose `data` exceeds `maxEventSize`, fails with
  * [[ServerSentEventParser.EventTooLargeException]].
  *
  * @param maxLineLength
  *   maximum length (chars) of a single line
  * @param maxEventSize
  *   maximum total length (chars) of the `data` lines of one event
  */
class ServerSentEventParser(
    maxLineLength: Int = ServerSentEventParser.DefaultMaxLineLength,
    maxEventSize: Int = ServerSentEventParser.DefaultMaxEventSize
):
  private var currentId: Option[String]         = None
  private var currentEvent: Option[String]      = None
  private var currentRetry: Option[Long]        = None
  private val currentData: ArrayBuffer[String]  = ArrayBuffer.empty
  private var currentDataSize: Int              = 0
  private val buffer                            = java.lang.StringBuilder()
  private var hasFieldsForCurrentEvent: Boolean = false

  /**
    * Feeds data into the parser and returns any complete events.
    *
    * This method can be called multiple times with chunks of data. It will buffer incomplete lines
    * and emit complete events as they are parsed.
    *
    * @param chunk
    *   The data to parse (may contain partial or multiple events)
    * @return
    *   A sequence of complete events
    */
  def feed(chunk: String): Seq[ServerSentEvent] =
    val events = ArrayBuffer.empty[ServerSentEvent]
    // Scan only the new chunk for line ends; the incomplete line carried in `buffer` is never
    // re-scanned, so feeding a long line in small chunks stays linear.
    var lineStart = 0
    var i         = 0
    while i < chunk.length do
      if chunk.charAt(i) == '\n' then
        buffer.append(chunk, lineStart, i)
        // A CRLF line end leaves the CR at the end of the line
        if buffer.length > 0 && buffer.charAt(buffer.length - 1) == '\r' then
          buffer.setLength(buffer.length - 1)
        checkLineLength()
        val line = buffer.toString
        buffer.setLength(0)
        parseLine(line).foreach(events += _)
        lineStart = i + 1
      i += 1
    buffer.append(chunk, lineStart, chunk.length)
    checkLineLength()
    events.toSeq

  end feed

  /**
    * Flushes any remaining buffered data and returns any pending event.
    *
    * Call this when the stream ends to ensure the last event is emitted even if it wasn't followed
    * by a blank line.
    *
    * @return
    *   The final event if there is pending data, None otherwise
    */
  def flush(): Option[ServerSentEvent] =
    if buffer.length > 0 then
      val line = buffer.toString
      buffer.setLength(0)
      parseLine(line)
    emitEvent()

  /**
    * Resets the parser state, clearing all buffered data.
    */
  def reset(): Unit =
    currentId = None
    currentEvent = None
    currentRetry = None
    currentData.clear()
    currentDataSize = 0
    buffer.setLength(0)
    hasFieldsForCurrentEvent = false

  private def checkLineLength(): Unit =
    if buffer.length > maxLineLength then
      buffer.setLength(0)
      throw ServerSentEventParser.EventTooLargeException(
        s"SSE line exceeds the maximum length of ${maxLineLength} chars"
      )

  private def addData(value: String): Unit =
    currentDataSize += value.length
    if currentDataSize > maxEventSize then
      currentData.clear()
      currentDataSize = 0
      throw ServerSentEventParser.EventTooLargeException(
        s"SSE event data exceeds the maximum size of ${maxEventSize} chars"
      )
    currentData += value

  private def parseLine(line: String): Option[ServerSentEvent] =
    if line.isEmpty then
      // Blank line signals end of event
      emitEvent()
    else if line.startsWith(":") then
      // Comment, ignore
      None
    else
      val colonIndex = line.indexOf(':')
      if colonIndex > 0 then
        val field = line.substring(0, colonIndex)
        // Skip the colon and optional space
        val value =
          if colonIndex + 1 < line.length && line.charAt(colonIndex + 1) == ' ' then
            line.substring(colonIndex + 2)
          else
            line.substring(colonIndex + 1)

        field match
          case "id" =>
            currentId = Some(value)
            hasFieldsForCurrentEvent = true
          case "event" =>
            currentEvent = Some(value)
            hasFieldsForCurrentEvent = true
          case "retry" =>
            value
              .toLongOption
              .foreach { v =>
                currentRetry = Some(v)
                hasFieldsForCurrentEvent = true
              }
          case "data" =>
            addData(value)
            hasFieldsForCurrentEvent = true
          case _ =>
          // Unknown field, ignore
      else if colonIndex == 0 then
        // Line starts with colon, treat as comment
        ()
      else
        // Line with no colon, treat entire line as field name with empty value
        line match
          case "data" =>
            addData("")
            hasFieldsForCurrentEvent = true
          case _ =>
          // Unknown field, ignore
      end if
      None

  private def emitEvent(): Option[ServerSentEvent] =
    if hasFieldsForCurrentEvent then
      val event = ServerSentEvent(
        id = currentId,
        event = currentEvent,
        retry = currentRetry,
        data = currentData.mkString("\n")
      )
      // Reset for next event, but keep lastEventId for reconnection
      currentEvent = None
      currentRetry = None
      currentData.clear()
      currentDataSize = 0
      hasFieldsForCurrentEvent = false
      Some(event)
    else
      None

end ServerSentEventParser

object ServerSentEventParser:
  /** Default maximum length of one SSE line: 1Mi chars. */
  final val DefaultMaxLineLength: Int = 1024 * 1024

  /** Default maximum total `data` length of one SSE event: 8Mi chars. */
  final val DefaultMaxEventSize: Int = 8 * 1024 * 1024

  /** Thrown when an SSE stream exceeds the parser's line or event size limit. */
  class EventTooLargeException(message: String) extends IllegalStateException(message)

  /**
    * Creates a new SSE parser.
    */
  def apply(
      maxLineLength: Int = DefaultMaxLineLength,
      maxEventSize: Int = DefaultMaxEventSize
  ): ServerSentEventParser = new ServerSentEventParser(maxLineLength, maxEventSize)

  /**
    * Parses a complete SSE stream and returns all events.
    *
    * @param content
    *   The complete SSE content
    * @return
    *   All events in the content
    */
  def parse(content: String): Seq[ServerSentEvent] =
    val parser = ServerSentEventParser()
    val events = parser.feed(content)
    parser.flush() match
      case Some(finalEvent) =>
        events :+ finalEvent
      case None =>
        events

end ServerSentEventParser
