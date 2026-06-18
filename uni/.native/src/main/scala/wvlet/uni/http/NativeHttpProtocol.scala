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
  * A minimal HTTP/1.1 request reader over a byte-chunk source. Handles keep-alive (multiple
  * requests per connection) by retaining any bytes read past the current request. Bodies are read
  * by `Content-Length`; chunked request bodies are not supported (MVP) and yield a `BadRequest`.
  *
  * @param readChunk
  *   reads the next batch of bytes from the socket; an empty array means EOF.
  */
private[http] class HttpConnectionReader(readChunk: () => Array[Byte], maxRequestSize: Int):

  private var buf: Array[Byte] = Array.emptyByteArray

  /** Append another chunk to the buffer. Returns false on EOF. */
  private def fill(): Boolean =
    val chunk = readChunk()
    if chunk.isEmpty then
      false
    else
      buf = buf ++ chunk
      true

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
        // Clean EOF with no pending bytes = connection closed; partial data = malformed.
        return if buf.isEmpty then
          ReadResult.Closed
        else
          ReadResult.BadRequest("Incomplete request")
      endIdx = headerEnd

    // Header block is bytes [0, endIdx-4); headers are ASCII/Latin-1.
    val headerText = new String(buf, 0, endIdx - 4, StandardCharsets.ISO_8859_1)
    val lines      = headerText.split("\r\n", -1)
    if lines.isEmpty || lines(0).isEmpty then
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

    val contentLength = headers.get(HttpHeader.ContentLength).flatMap(_.toIntOption).getOrElse(0)
    if contentLength < 0 || contentLength > maxRequestSize then
      return ReadResult.BadRequest("Invalid Content-Length")

    // Ensure the full body is buffered.
    while buf.length - endIdx < contentLength do
      if !fill() then
        return ReadResult.BadRequest("Incomplete request body")

    val body = buf.slice(endIdx, endIdx + contentLength)
    // Retain any bytes past this request for the next (pipelined/keep-alive) read.
    buf = buf.drop(endIdx + contentLength)

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

  def serialize(response: Response, keepAlive: Boolean): Array[Byte] =
    val body = response.content.toContentBytes
    val sb   = StringBuilder()
    sb.append("HTTP/1.1 ")
      .append(response.status.code)
      .append(" ")
      .append(response.status.reason)
      .append("\r\n")

    // Copy response headers, but skip the ones we set explicitly below to avoid duplicates.
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !isManagedHeader(name) then
          sb.append(name).append(": ").append(value).append("\r\n")
      }

    response
      .content
      .contentType
      .foreach { ct =>
        sb.append(HttpHeader.ContentType).append(": ").append(ct.value).append("\r\n")
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
    if body.isEmpty then
      head
    else
      head ++ body

  end serialize

  private def isManagedHeader(name: String): Boolean =
    name.equalsIgnoreCase(HttpHeader.ContentType) ||
      name.equalsIgnoreCase(HttpHeader.ContentLength) ||
      name.equalsIgnoreCase(HttpHeader.Connection)

end HttpResponseWriter
