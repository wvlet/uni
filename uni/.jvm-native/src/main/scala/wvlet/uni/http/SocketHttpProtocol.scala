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

/**
  * Result of reading one request from a keep-alive connection.
  */
private[http] enum ReadResult:
  case Req(request: Request)
  case BadRequest(message: String)
  case Closed

/**
  * A minimal HTTP/1.1 request reader over a connection. Handles keep-alive (multiple requests per
  * connection) by retaining any bytes read past the current request. Bodies are read by
  * `Content-Length`; chunked request bodies are not supported and yield a `BadRequest`.
  *
  * Bytes go straight from the socket into one growable `Array[Byte]`, and the request line and
  * headers are parsed from it in place, so a request costs no per-byte boxing or line splitting.
  *
  * @param read
  *   reads bytes from the socket into `(buf, off, len)`; returns the number read, or 0 or less on
  *   EOF or timeout. The `idle` argument is true when waiting for the first bytes of a new request
  *   (nothing is buffered) and false once a request has started arriving, so the caller can apply
  *   an idle vs a read timeout.
  */
private[http] class HttpConnectionReader(
    read: (Array[Byte], Int, Int, Boolean) => Int,
    maxRequestSize: Int
):
  import HttpConnectionReader.*

  private var buf = new Array[Byte](InitialBufferSize)
  // Unconsumed bytes are buf[start, end)
  private var start = 0
  private var end   = 0
  // Where the search for the header terminator resumes, so a request that arrives in many reads is
  // scanned once rather than from its start after every read
  private var scanFrom = 0

  private def buffered: Int = end - start

  /** Read more bytes into the buffer, compacting or growing it first if full. False on EOF. */
  private def fill(): Boolean =
    if end == buf.length then
      if start > 0 then
        System.arraycopy(buf, start, buf, 0, buffered)
        scanFrom -= start
        end -= start
        start = 0
      if end == buf.length then
        buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    val n = read(buf, end, buf.length - end, buffered == 0)
    if n <= 0 then
      false
    else
      end += n
      true

  private def isHttpWhitespace(b: Byte): Boolean = b == '\r' || b == '\n' || b == ' ' || b == '\t'

  private def onlyWhitespaceBuffered: Boolean =
    var i = start
    while i < end && isHttpWhitespace(buf(i)) do
      i += 1
    i == end

  /** Index just past the `\r\n\r\n` header terminator, or -1 if not yet present. */
  private def headerEnd: Int =
    var i     = math.max(start, scanFrom)
    val limit = end - 3
    while i < limit do
      if buf(i + 3) != '\n' then
        // The terminator can't end at i + 3, so skip ahead
        i += 1
      else if buf(i) == '\r' && buf(i + 1) == '\n' && buf(i + 2) == '\r' then
        return i + 4
      else
        i += 1
    scanFrom = math.max(start, end - 3)
    -1

  /** Index of the next `\r\n` in buf[from, limit), or `limit` if there is none. */
  private def lineEnd(from: Int, limit: Int): Int =
    var i = from
    while i + 1 < limit do
      if buf(i) == '\r' && buf(i + 1) == '\n' then
        return i
      i += 1
    limit

  private def latin1(from: Int, until: Int): String = String(
    buf,
    from,
    until - from,
    StandardCharsets.ISO_8859_1
  )

  /** A Latin-1 string of buf[from, until) without leading and trailing spaces and tabs. */
  private def trimmed(from: Int, until: Int): String =
    var s = from
    var e = until
    while s < e && (buf(s) == ' ' || buf(s) == '\t') do
      s += 1
    while e > s && (buf(e - 1) == ' ' || buf(e - 1) == '\t') do
      e -= 1
    latin1(s, e)

  def readRequest(): ReadResult =
    // Accumulate until the header block is complete.
    var endIdx = headerEnd
    while endIdx < 0 do
      if buffered > maxRequestSize then
        return ReadResult.BadRequest("Request header too large")
      if !fill() then
        // Clean EOF: an empty or whitespace-only buffer (e.g. a trailing CRLF after a keep-alive
        // request) means the connection closed; anything else is a truncated request.
        return if onlyWhitespaceBuffered then
          ReadResult.Closed
        else
          ReadResult.BadRequest("Incomplete request")
      endIdx = headerEnd

    // The header block is buf[start, blockEnd); headers are ASCII/Latin-1.
    val blockEnd = endIdx - 4
    // Per RFC 7230, tolerate empty line(s) before the request line.
    var lineStart = start
    var lineStop  = lineEnd(lineStart, blockEnd)
    while lineStop == lineStart && lineStart < blockEnd do
      lineStart += 2
      lineStop = lineEnd(lineStart, blockEnd)
    if lineStart >= blockEnd then
      return ReadResult.BadRequest("Empty request line")

    // Request line: METHOD SP target [SP version]
    var sp = lineStart
    while sp < lineStop && buf(sp) != ' ' do
      sp += 1
    var targetEnd = sp + 1
    while targetEnd < lineStop && buf(targetEnd) != ' ' do
      targetEnd += 1
    var rest = sp + 1
    while rest < lineStop && buf(rest) == ' ' do
      rest += 1
    if sp >= lineStop || rest >= lineStop then
      return ReadResult.BadRequest("Malformed request line")
    val methodName = latin1(lineStart, sp)
    val target     = Request.decodeRequestTarget(latin1(sp + 1, targetEnd))

    val headerBuilder = HttpMultiMap.newBuilder
    lineStart = lineStop + 2
    while lineStart < blockEnd do
      lineStop = lineEnd(lineStart, blockEnd)
      var colon = lineStart
      while colon < lineStop && buf(colon) != ':' do
        colon += 1
      if colon > lineStart && colon < lineStop then
        headerBuilder.add(trimmed(lineStart, colon), trimmed(colon + 1, lineStop))
      lineStart = lineStop + 2
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

    // Ensure the full body is buffered. fill() may move the buffer's contents, so track the body by
    // its offset from start.
    val bodyOffset = endIdx - start
    while buffered - bodyOffset < contentLength do
      if !fill() then
        return ReadResult.BadRequest("Incomplete request body")

    val bodyStart = start + bodyOffset
    val body      =
      if contentLength == 0 then
        Array.emptyByteArray
      else
        java.util.Arrays.copyOfRange(buf, bodyStart, bodyStart + contentLength)
    // Consume the request, retaining any leftover for the next (pipelined/keep-alive) request.
    start = bodyStart + contentLength
    if start == end then
      start = 0
      end = 0
    scanFrom = start

    HttpMethod.of(methodName) match
      case Some(method) =>
        val content = HttpContent.fromBytes(body, headers)
        ReadResult.Req(Request(method = method, uri = target, headers = headers, content = content))
      case None =>
        ReadResult.BadRequest(s"Unsupported HTTP method: ${methodName}")

  end readRequest

end HttpConnectionReader

private[http] object HttpConnectionReader:
  private final val InitialBufferSize = 8192

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
    val body = response.content.toContentBytes
    val head = serializeHead(response, keepAlive, body.length.toLong)
    if includeBody && !bodyForbidden(response) && body.nonEmpty then
      head ++ body
    else
      head

  // 1xx, 204, and 304 responses must not carry a message body or Content-Length.
  def bodyForbidden(response: Response): Boolean =
    val code = response.status.code
    code == 204 || code == 304 || (code >= 100 && code < 200)

  /** The status line and headers of a response whose body has `contentLength` bytes. */
  def serializeHead(response: Response, keepAlive: Boolean, contentLength: Long): Array[Byte] =
    val code = response.status.code
    val sb   = StringBuilder()
    sb.append("HTTP/1.1 ").append(code).append(" ").append(response.status.reason).append("\r\n")

    // Copy response headers, but skip the ones we set explicitly below to avoid duplicates.
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !isManagedHeader(name) then
          sb.append(name).append(": ").append(value).append("\r\n")
      }

    if !bodyForbidden(response) then
      // Prefer an explicitly-set Content-Type header over the content's default type.
      val contentType = response
        .headers
        .get(HttpHeader.ContentType)
        .orElse(response.content.contentType.map(_.value))
      contentType.foreach { ct =>
        sb.append(HttpHeader.ContentType).append(": ").append(ct).append("\r\n")
      }
      sb.append(HttpHeader.ContentLength).append(": ").append(contentLength).append("\r\n")

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

    sb.toString.getBytes(StandardCharsets.ISO_8859_1)

  end serializeHead

  private def isManagedHeader(name: String): Boolean =
    name.equalsIgnoreCase(HttpHeader.ContentType) ||
      name.equalsIgnoreCase(HttpHeader.ContentLength) ||
      name.equalsIgnoreCase(HttpHeader.Connection)

  /**
    * Serialize the status line + headers for a streaming (SSE) response: `text/event-stream` with
    * `Transfer-Encoding: chunked` and no `Content-Length`. The body is then written as chunks via
    * [[chunk]] / [[finalChunk]]. Mirrors the Netty/Node SSE response.
    */
  def serializeSseHeaders(response: Response, keepAlive: Boolean): Array[Byte] =
    val sb = StringBuilder()
    sb.append("HTTP/1.1 ")
      .append(response.status.code)
      .append(" ")
      .append(response.status.reason)
      .append("\r\n")
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !isManagedHeader(name) && !name.equalsIgnoreCase(HttpHeader.TransferEncoding) &&
          !name.equalsIgnoreCase(HttpHeader.CacheControl)
        then
          sb.append(name).append(": ").append(value).append("\r\n")
      }
    sb.append(HttpHeader.ContentType)
      .append(": ")
      .append(ContentType.TextEventStream.value)
      .append("\r\n")
    sb.append(HttpHeader.CacheControl).append(": no-cache\r\n")
    sb.append(HttpHeader.TransferEncoding).append(": chunked\r\n")
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
    sb.toString.getBytes(StandardCharsets.ISO_8859_1)

  end serializeSseHeaders

  /**
    * One HTTP chunked-transfer chunk: `<hex-length>\r\n<data>\r\n`. Empty data yields no bytes (a
    * zero-length chunk would prematurely terminate the body).
    */
  def chunk(data: Array[Byte]): Array[Byte] =
    if data.isEmpty then
      Array.emptyByteArray
    else
      val header = s"${Integer.toHexString(data.length)}\r\n".getBytes(StandardCharsets.ISO_8859_1)
      header ++ data ++ CRLF

  /** The terminating zero-length chunk that ends a chunked body. */
  val finalChunk: Array[Byte] = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)

  private val CRLF: Array[Byte] = "\r\n".getBytes(StandardCharsets.ISO_8859_1)

end HttpResponseWriter
