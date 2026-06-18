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

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Result of reading one request from a keep-alive connection.
  */
private[http] enum ReadResult:
  case Req(request: Request)
  case BadRequest(message: String)
  case Closed

/**
  * A minimal HTTP/1.1 request reader over a byte-chunk source. Handles keep-alive (multiple
  * requests per connection) by retaining any bytes read past the current request. Bodies are read
  * by `Content-Length`; chunked request bodies are not supported (MVP) and yield a `BadRequest`.
  *
  * @param readChunk
  *   reads the next batch of bytes from the socket; an empty array means EOF.
  */
private[http] class HttpConnectionReader(readChunk: () => Array[Byte], maxRequestSize: Int):

  // Amortized-O(1) append buffer (indexed scan, single slice per request) — avoids the O(n^2) of
  // repeatedly concatenating Array[Byte].
  private val buf = mutable.ArrayBuffer.empty[Byte]

  /** Append another chunk to the buffer. Returns false on EOF. */
  private def fill(): Boolean =
    val chunk = readChunk()
    if chunk.isEmpty then
      false
    else
      buf ++= chunk
      true

  private def isHttpWhitespace(b: Byte): Boolean = b == '\r' || b == '\n' || b == ' ' || b == '\t'

  /** Index just past the `\r\n\r\n` header terminator, or -1 if not yet present. */
  private def headerEnd: Int =
    var i = 0
    val n = buf.length - 3
    while i < n do
      if buf(i) == '\r' && buf(i + 1) == '\n' && buf(i + 2) == '\r' && buf(i + 3) == '\n' then
        return i + 4
      i += 1
    -1

  def readRequest(): ReadResult =
    // Accumulate until the header block is complete.
    var endIdx = headerEnd
    while endIdx < 0 do
      if buf.length > maxRequestSize then
        return ReadResult.BadRequest("Request header too large")
      if !fill() then
        // Clean EOF: an empty or whitespace-only buffer (e.g. a trailing CRLF after a keep-alive
        // request) means the connection closed; anything else is a truncated request.
        return if buf.forall(isHttpWhitespace) then
          ReadResult.Closed
        else
          ReadResult.BadRequest("Incomplete request")
      endIdx = headerEnd

    // Header block is bytes [0, endIdx-4); headers are ASCII/Latin-1.
    val headerText = new String(buf.slice(0, endIdx - 4).toArray, StandardCharsets.ISO_8859_1)
    // Per RFC 7230, tolerate empty line(s) before the request line.
    val lines = headerText.split("\r\n", -1).dropWhile(_.isEmpty)
    if lines.isEmpty then
      return ReadResult.BadRequest("Empty request line")

    val requestLine = lines(0).split(" ")
    if requestLine.length < 2 then
      return ReadResult.BadRequest("Malformed request line")
    val methodName = requestLine(0)
    val target     = requestLine(1)

    val headerBuilder = HttpMultiMap.newBuilder
    var li            = 1
    while li < lines.length do
      val line = lines(li)
      if line.nonEmpty then
        val colon = line.indexOf(':')
        if colon > 0 then
          headerBuilder.add(line.substring(0, colon).trim, line.substring(colon + 1).trim)
      li += 1
    val headers = headerBuilder.result()

    if headers.get(HttpHeader.TransferEncoding).exists(_.toLowerCase.contains("chunked")) then
      return ReadResult.BadRequest("Chunked request bodies are not supported")

    // Resolve Content-Length defensively: reject an unparseable/oversized value or conflicting
    // duplicate headers, rather than silently treating the body as empty (which would leave the
    // declared body in the buffer and desync the next keep-alive read — request smuggling).
    val contentLengthValues = headers.getAll(HttpHeader.ContentLength).distinct
    val contentLength       =
      if contentLengthValues.isEmpty then
        0
      else if contentLengthValues.sizeIs > 1 then
        return ReadResult.BadRequest("Conflicting Content-Length headers")
      else
        contentLengthValues.head.toIntOption match
          case Some(n) if n >= 0 && n <= maxRequestSize =>
            n
          case _ =>
            return ReadResult.BadRequest("Invalid Content-Length")

    // Ensure the full body is buffered.
    while buf.length - endIdx < contentLength do
      if !fill() then
        return ReadResult.BadRequest("Incomplete request body")

    val body = buf.slice(endIdx, endIdx + contentLength).toArray
    // Drop the consumed bytes, retaining any leftover for the next (pipelined/keep-alive) request.
    buf.remove(0, endIdx + contentLength)

    HttpMethod.of(methodName) match
      case Some(method) =>
        val content = HttpContent.fromBytes(body, headers)
        ReadResult.Req(Request(method = method, uri = target, headers = headers, content = content))
      case None =>
        ReadResult.BadRequest(s"Unsupported HTTP method: ${methodName}")

  end readRequest

end HttpConnectionReader

/**
  * Serializes a [[Response]] to HTTP/1.1 wire bytes.
  */
private[http] object HttpResponseWriter:

  /**
    * @param includeBody
    *   false for responses to HEAD requests — headers (incl. Content-Length) are written but the
    *   body bytes are omitted, per RFC 9110.
    */
  def serialize(response: Response, keepAlive: Boolean, includeBody: Boolean): Array[Byte] =
    val code = response.status.code
    // 1xx, 204, and 304 responses must not carry a message body or Content-Length.
    val bodyForbidden = code == 204 || code == 304 || (code >= 100 && code < 200)
    val body          = response.content.toContentBytes

    val sb = StringBuilder()
    sb.append("HTTP/1.1 ").append(code).append(" ").append(response.status.reason).append("\r\n")

    // Copy response headers, but skip the ones we set explicitly below to avoid duplicates.
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !isManagedHeader(name) then
          sb.append(name).append(": ").append(value).append("\r\n")
      }

    if !bodyForbidden then
      // Prefer an explicitly-set Content-Type header over the content's default type.
      val contentType = response
        .headers
        .get(HttpHeader.ContentType)
        .orElse(response.content.contentType.map(_.value))
      contentType.foreach { ct =>
        sb.append(HttpHeader.ContentType).append(": ").append(ct).append("\r\n")
      }
      sb.append(HttpHeader.ContentLength).append(": ").append(body.length).append("\r\n")

    sb.append(HttpHeader.Connection)
      .append(": ")
      .append(
        if keepAlive then
          "keep-alive"
        else
          "close"
      )
      .append("\r\n")
    sb.append("\r\n")

    val head = sb.toString.getBytes(StandardCharsets.ISO_8859_1)
    if includeBody && !bodyForbidden && body.nonEmpty then
      head ++ body
    else
      head

  end serialize

  private def isManagedHeader(name: String): Boolean =
    name.equalsIgnoreCase(HttpHeader.ContentType) ||
      name.equalsIgnoreCase(HttpHeader.ContentLength) ||
      name.equalsIgnoreCase(HttpHeader.Connection)

end HttpResponseWriter
