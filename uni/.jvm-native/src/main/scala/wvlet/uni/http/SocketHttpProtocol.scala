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
  /** A request; `keepAlive` tells whether the connection may serve another one after it. */
  case Req(request: Request, keepAlive: Boolean)

  /** A request the server must reject with `status`, then close the connection. */
  case Error(status: HttpStatus, message: String)
  case Closed

/**
  * Size limits of one request, as Netty's `HttpServerCodec` and `HttpObjectAggregator` set them.
  *
  * @param maxInitialLineLength
  *   the request line, and the size line of a chunk (414 when exceeded)
  * @param maxHeaderSize
  *   the header block after the request line, and the trailers of a chunked body (431)
  * @param maxContentLength
  *   the body as sent, or after chunked decoding (413)
  */
private[http] case class HttpReaderLimits(
    maxInitialLineLength: Int = 4096,
    maxHeaderSize: Int = 8192,
    maxContentLength: Int = 1024 * 1024
)

/**
  * An HTTP/1.1 request reader over a connection. Handles keep-alive (multiple requests per
  * connection) by retaining any bytes read past the current request. Bodies are framed by
  * `Content-Length` or `Transfer-Encoding: chunked`.
  *
  * The reader is strict where a lenient parser would let a front proxy and this server disagree on
  * where a request ends (request smuggling): a bare CR or LF, obs-fold, a header name that is not a
  * token, whitespace before the colon, control characters in values, a non-numeric or repeated
  * `Content-Length`, and a `Transfer-Encoding` whose final coding is not `chunked` are all
  * rejected. `spec/lean/UniSpec/HttpReader.lean` models these rules.
  *
  * Bytes go straight from the socket into one growable `Array[Byte]`, and the request is parsed
  * from it in place, so a request costs no per-byte boxing or line splitting.
  *
  * @param read
  *   reads bytes from the socket into `(buf, off, len)`; returns the number read, 0 on timeout, or
  *   less than 0 on EOF. The `idle` argument is true when waiting for the first bytes of a new
  *   request (nothing is buffered) and false once a request has started arriving, so the caller can
  *   apply an idle timeout vs a request deadline.
  * @param sendContinue
  *   writes `100 Continue` for a request with `Expect: 100-continue`, before its body is read.
  *   Returns false when the connection is gone.
  */
private[http] class HttpConnectionReader(
    read: (Array[Byte], Int, Int, Boolean) => Int,
    limits: HttpReaderLimits,
    sendContinue: () => Boolean = () => true
):
  import HttpConnectionReader.*

  private var buf = new Array[Byte](InitialBufferSize)
  // Unconsumed bytes are buf[start, end). While a request is read, positions are relative to start,
  // because fill() may move the bytes.
  private var start = 0
  private var end   = 0

  private def buffered: Int = end - start

  // The byte at a position relative to start
  private inline def at(rel: Int): Int = buf(start + rel) & 0xff

  /**
    * Read more bytes into the buffer, compacting or growing it first if full. Returns the bytes
    * read: 0 on timeout, less than 0 on EOF.
    */
  private def fill(): Int =
    if end == buf.length then
      if start > 0 then
        System.arraycopy(buf, start, buf, 0, buffered)
        end -= start
        start = 0
      if end == buf.length then
        buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    val n = read(buf, end, buf.length - end, buffered == 0)
    if n > 0 then
      end += n
    n

  /** Make the byte at `rel` available. Returns > 0 when it is, 0 on timeout, < 0 on EOF. */
  private def ensure(rel: Int): Int =
    var n = 1
    while n > 0 && buffered <= rel do
      n = fill()
    n

  private def onlyWhitespaceBuffered: Boolean =
    var i = start
    while i < end && isHttpWhitespace(buf(i) & 0xff) do
      i += 1
    i == end

  private def latin1(from: Int, until: Int): String = String(
    buf,
    start + from,
    until - from,
    StandardCharsets.ISO_8859_1
  )

  private def bad(message: String): ReadResult = ReadResult.Error(
    HttpStatus.BadRequest_400,
    message
  )

  /** The result of a read that ended before the request did. */
  private def cutShort(n: Int, message: String): ReadResult =
    if n == 0 then
      ReadResult.Error(HttpStatus.RequestTimeout_408, "Request timeout")
    else
      bad(message)

  /**
    * Validate one header (or trailer) line at [from, until) and return the 400 message, or null.
    * The name and trimmed value are stored in `nameEnd`/`valueStart`/`valueEnd`.
    */
  private var nameEnd    = 0
  private var valueStart = 0
  private var valueEnd   = 0

  private def checkHeaderLine(from: Int, until: Int): String =
    val first = at(from)
    if first == ' ' || first == '\t' then
      return "Obsolete line folding"
    var colon = from
    while colon < until && at(colon) != ':' do
      colon += 1
    if colon == until then
      return "Invalid header line"
    if colon == from then
      return "Invalid header name"
    var i = from
    while i < colon do
      if !isTokenChar(at(i)) then
        return "Invalid header name"
      i += 1
    var vs = colon + 1
    var ve = until
    while vs < ve && isBlank(at(vs)) do
      vs += 1
    while ve > vs && isBlank(at(ve - 1)) do
      ve -= 1
    i = vs
    while i < ve do
      if isInvalidValueChar(at(i)) then
        return "Invalid header value"
      i += 1
    nameEnd = colon
    valueStart = vs
    valueEnd = ve
    null

  end checkHeaderLine

  /**
    * Scan lines from `from` until an empty line, checking line endings and the size limit.
    * `onLine(lineStart, lineEnd)` sees each non-empty line and returns a 400 message or null.
    * Returns the position after the empty line's LF, or -1 with the error in `scanError`.
    */
  private var scanError: ReadResult = null

  private def scanLines(
      from: Int,
      maxSize: Int,
      tooLarge: ReadResult,
      onEof: String,
      onLine: (Int, Int) => String
  ): Int =
    var i         = from
    var lineStart = from
    while true do
      val n = ensure(i)
      if n <= 0 then
        scanError = cutShort(n, onEof)
        return -1
      val b = at(i)
      if b == '\n' then
        if i == 0 || at(i - 1) != '\r' then
          scanError = bad("Invalid line ending")
          return -1
        val lineEnd = i - 1
        if lineEnd == lineStart then
          return i + 1
        val err = onLine(lineStart, lineEnd)
        if err != null then
          scanError = bad(err)
          return -1
        lineStart = i + 1
      else if i > from && at(i - 1) == '\r' then
        scanError = bad("Invalid line ending")
        return -1
      else if b != '\r' && i - from >= maxSize then
        scanError = tooLarge
        return -1
      i += 1
    -1

  end scanLines

  def readRequest(): ReadResult =
    // Skip CR and LF before the request line (RFC 9112 §2.2), as Netty does
    var skipping = true
    while skipping do
      while buffered > 0 && (buf(start) == '\r' || buf(start) == '\n') do
        start += 1
      if buffered > 0 then
        skipping = false
      else
        start = 0
        end = 0
        if fill() <= 0 then
          return ReadResult.Closed

    // The request line: scan to its LF, checking line endings and its length
    var i       = 0
    var lineEnd = -1
    while lineEnd < 0 do
      val n = ensure(i)
      if n <= 0 then
        return if n < 0 && onlyWhitespaceBuffered then
          ReadResult.Closed
        else
          cutShort(n, "Incomplete request")
      val b = at(i)
      if b == '\n' then
        if at(i - 1) != '\r' then
          return bad("Invalid line ending")
        lineEnd = i - 1
      else if i > 0 && at(i - 1) == '\r' then
        return bad("Invalid line ending")
      else if b != '\r' && i >= limits.maxInitialLineLength then
        return ReadResult.Error(HttpStatus.UriTooLong_414, "Request line too long")
      i += 1

    // METHOD SP+ target SP+ HTTP/1.x
    var sp = 0
    while sp < lineEnd && at(sp) != ' ' do
      sp += 1
    if sp == 0 || sp == lineEnd then
      return bad("Malformed request line")
    var targetStart = sp
    while targetStart < lineEnd && at(targetStart) == ' ' do
      targetStart += 1
    var targetEnd = targetStart
    while targetEnd < lineEnd && at(targetEnd) != ' ' do
      targetEnd += 1
    if targetEnd == targetStart then
      return bad("Malformed request line")
    var versionStart = targetEnd
    while versionStart < lineEnd && at(versionStart) == ' ' do
      versionStart += 1
    if versionStart == lineEnd then
      return bad("Malformed request line")
    var k = versionStart
    while k < lineEnd do
      if at(k) == ' ' then
        return bad("Malformed request line")
      k += 1
    val http10 =
      if lineEnd - versionStart == 8 && latin1(versionStart, versionStart + 5) == "HTTP/" &&
        isDigit(at(versionStart + 5)) && at(versionStart + 6) == '.' &&
        isDigit(at(versionStart + 7))
      then
        val major = at(versionStart + 5)
        val minor = at(versionStart + 7)
        if major == '1' && minor == '1' then
          false
        else if major == '1' && minor == '0' then
          true
        else
          return ReadResult.Error(
            HttpStatus.HttpVersionNotSupported_505,
            "HTTP version not supported"
          )
      else
        return bad("Invalid HTTP version")
    k = 0
    while k < sp do
      if !isTokenChar(at(k)) then
        return bad("Invalid method")
      k += 1
    k = targetStart
    while k < targetEnd do
      if isControl(at(k)) then
        return bad("Invalid request target")
      k += 1
    val methodName = latin1(0, sp)
    val target     = latin1(targetStart, targetEnd)

    // Headers, up to the empty line
    val headerBuilder = HttpMultiMap.newBuilder
    val headStart     = lineEnd + 2
    val headEnd       = scanLines(
      headStart,
      limits.maxHeaderSize,
      ReadResult.Error(HttpStatus.HeaderFieldsTooLarge_431, "Request header too large"),
      "Incomplete request",
      (from, until) =>
        val err = checkHeaderLine(from, until)
        if err == null then
          headerBuilder.add(latin1(from, nameEnd), latin1(valueStart, valueEnd))
        err
    )
    if headEnd < 0 then
      return scanError
    var headers = headerBuilder.result()

    // Method names are case-sensitive (RFC 9110 §9.1); uni-netty answers an unknown one with 501
    val method =
      HttpMethod.of(methodName).filter(_.name == methodName) match
        case Some(m) =>
          m
        case None =>
          return ReadResult.Error(
            HttpStatus.NotImplemented_501,
            s"Unsupported HTTP method: ${methodName}"
          )

    if headers.getAll(HttpHeader.Host).sizeIs > 1 then
      return bad("Multiple Host headers")

    // Framing (RFC 9112 §6). A request with both Transfer-Encoding and Content-Length may be an
    // attempt to smuggle a request past a proxy that reads the other one: decode it by
    // Transfer-Encoding, as the RFC says, and close the connection after the response.
    val transferEncodings = headers.getAll(HttpHeader.TransferEncoding)
    val contentLengths    = headers.getAll(HttpHeader.ContentLength)
    val chunked           = transferEncodings.nonEmpty
    var contentLength     = 0
    if chunked then
      if http10 then
        return bad("Transfer-Encoding is not allowed in HTTP/1.0")
      val codings = tokens(transferEncodings)
      if codings.last != "chunked" then
        return bad("Invalid Transfer-Encoding")
      if codings.sizeIs > 1 then
        return ReadResult.Error(HttpStatus.NotImplemented_501, "Unsupported Transfer-Encoding")
    else if contentLengths.nonEmpty then
      if contentLengths.sizeIs > 1 || contentLengths.head.contains(',') then
        return bad("Multiple Content-Length values")
      val v = contentLengths.head
      if v.isEmpty || !v.forall(c => c >= '0' && c <= '9') then
        return bad("Invalid Content-Length")
      // Cap while accumulating, so a huge value can't overflow
      var n = 0L
      v.foreach { c =>
        n = math.min(n * 10 + (c - '0'), limits.maxContentLength.toLong + 1)
      }
      if n > limits.maxContentLength then
        return ReadResult.Error(HttpStatus.PayloadTooLarge_413, "Request body too large")
      contentLength = n.toInt

    // Expect (RFC 9110 §10.1.1); HTTP/1.0 doesn't define it, so it is ignored there, as in Netty
    var expectContinue = false
    val expects        = headers.getAll(HttpHeader.Expect)
    if expects.nonEmpty && !http10 then
      if expects.sizeIs == 1 && expects.head.equalsIgnoreCase("100-continue") then
        expectContinue = true
        headers = headers.remove(HttpHeader.Expect)
      else
        return ReadResult.Error(HttpStatus.ExpectationFailed_417, "Unsupported expectation")

    // The client waits for 100 Continue before it sends the body. Skip it when the body has started
    // arriving anyway.
    if expectContinue && buffered == headEnd && (chunked || contentLength > 0) then
      if !sendContinue() then
        return ReadResult.Closed

    val connectionTokens = tokens(headers.getAll(HttpHeader.Connection))
    val keepAlive        =
      !connectionTokens.contains("close") &&
        (!http10 || connectionTokens.contains("keep-alive")) &&
        !(chunked && contentLengths.nonEmpty)

    val (body, bodyEnd) =
      if chunked then
        readChunked(headEnd) match
          case Left(error) =>
            return error
          case Right(result) =>
            result
      else
        val n = ensure(headEnd + contentLength - 1)
        if contentLength > 0 && n <= 0 then
          return cutShort(n, "Incomplete request body")
        val b =
          if contentLength == 0 then
            Array.emptyByteArray
          else
            java.util.Arrays.copyOfRange(buf, start + headEnd, start + headEnd + contentLength)
        (b, headEnd + contentLength)

    if chunked then
      // As Netty's aggregator does, the handler sees the decoded length rather than the framing
      headers = headers
        .remove(HttpHeader.TransferEncoding)
        .set(HttpHeader.ContentLength, body.length.toString)

    // Consume the request, retaining any leftover for the next (pipelined/keep-alive) request.
    start += bodyEnd
    if start == end then
      start = 0
      end = 0

    val request = Request(
      method = method,
      uri = Request.decodeRequestTarget(target),
      headers = headers,
      content = HttpContent.fromBytes(body, headers)
    )
    ReadResult.Req(request, keepAlive)

  end readRequest

  /**
    * Decode a chunked body starting at `from`: its bytes and the position after its last trailer
    * line, or the error. Chunk extensions are skipped and trailers dropped, as Netty's aggregator
    * does with them for uni-netty's handlers.
    */
  private def readChunked(from: Int): Either[ReadResult, (Array[Byte], Int)] =
    var out   = new Array[Byte](math.min(InitialBufferSize, limits.maxContentLength))
    var total = 0
    var p     = from
    while true do
      // chunk-size [; chunk-ext] CRLF
      val lineStart = p
      var size      = 0L
      var digits    = 0
      var b         = 0
      var reading   = true
      while reading do
        val n = ensure(p)
        if n <= 0 then
          return Left(cutShort(n, "Incomplete request body"))
        b = at(p)
        val v = hexValue(b)
        if v < 0 then
          reading = false
        else
          size = size * 16 + v
          digits += 1
          if size > limits.maxContentLength - total then
            return Left(ReadResult.Error(HttpStatus.PayloadTooLarge_413, "Request body too large"))
          p += 1
      if digits == 0 then
        return Left(bad("Invalid chunk size"))
      if b == ';' then
        // chunk-ext: anything but control characters, up to the CR
        p += 1
        var ext = true
        while ext do
          val n = ensure(p)
          if n <= 0 then
            return Left(cutShort(n, "Incomplete request body"))
          b = at(p)
          if b == '\r' || b == '\n' then
            ext = false
          else if isInvalidValueChar(b) then
            return Left(bad("Invalid chunk extension"))
          else if p - lineStart >= limits.maxInitialLineLength then
            return Left(bad("Chunk size line too long"))
          else
            p += 1
      val n = ensure(p + 1)
      if n <= 0 then
        return Left(cutShort(n, "Incomplete request body"))
      if at(p) != '\r' || at(p + 1) != '\n' then
        return Left(bad("Invalid chunk size"))
      p += 2
      if size == 0 then
        // Trailers, up to the empty line; they are checked like headers, then dropped
        val trailerEnd = scanLines(
          p,
          limits.maxHeaderSize,
          ReadResult.Error(HttpStatus.HeaderFieldsTooLarge_431, "Request trailers too large"),
          "Incomplete request body",
          (from, until) => checkHeaderLine(from, until)
        )
        if trailerEnd < 0 then
          return Left(scanError)
        return Right((java.util.Arrays.copyOf(out, total), trailerEnd))
      val len = size.toInt
      // chunk-data CRLF
      val m = ensure(p + len + 1)
      if m <= 0 then
        return Left(cutShort(m, "Incomplete request body"))
      if at(p + len) != '\r' || at(p + len + 1) != '\n' then
        return Left(bad("Invalid chunk data"))
      if total + len > out.length then
        out = java.util.Arrays.copyOf(out, math.max(out.length * 2, total + len))
      System.arraycopy(buf, start + p, out, total, len)
      total += len
      p += len + 2
    end while
    Left(bad("Unreachable"))

  end readChunked

end HttpConnectionReader

private[http] object HttpConnectionReader:
  private final val InitialBufferSize = 8192

  private inline def isHttpWhitespace(b: Int): Boolean =
    b == '\r' || b == '\n' || b == ' ' || b == '\t'

  private inline def isBlank(b: Int): Boolean = b == ' ' || b == '\t'

  private inline def isDigit(b: Int): Boolean = b >= '0' && b <= '9'

  private inline def isControl(b: Int): Boolean = b < 0x20 || b == 0x7f

  /** Control characters other than HTAB are not allowed in a field value (RFC 9110 §5.5). */
  private inline def isInvalidValueChar(b: Int): Boolean = (b < 0x20 && b != '\t') || b == 0x7f

  // tchar (RFC 9110 §5.6.2)
  private val tokenChars: Array[Boolean] =
    val a = new Array[Boolean](256)
    for c <- ('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z') ++ "!#$%&'*+-.^_`|~" do
      a(c) = true
    a

  private inline def isTokenChar(b: Int): Boolean = tokenChars(b)

  private def hexValue(b: Int): Int =
    if b >= '0' && b <= '9' then
      b - '0'
    else if b >= 'a' && b <= 'f' then
      b - 'a' + 10
    else if b >= 'A' && b <= 'F' then
      b - 'A' + 10
    else
      -1

  /**
    * The comma-separated list elements of header values, trimmed and ASCII-lower-cased (the default
    * locale could change `I`, and Scala Native has no `Locale.ROOT`).
    */
  private[http] def tokens(values: Seq[String]): Seq[String] = values.flatMap(
    _.split(",", -1).iterator.map(t => asciiLower(t.trim))
  )

  private def asciiLower(s: String): String =
    val chars = s.toCharArray
    var i     = 0
    while i < chars.length do
      val c = chars(i)
      if c >= 'A' && c <= 'Z' then
        chars(i) = (c + 32).toChar
      i += 1
    String(chars)

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

  // The writer frames every response itself, so a handler's Transfer-Encoding is dropped: next to
  // the writer's Content-Length it would let a client or proxy read a different body length
  private def isManagedHeader(name: String): Boolean =
    name.equalsIgnoreCase(HttpHeader.ContentType) ||
      name.equalsIgnoreCase(HttpHeader.ContentLength) ||
      name.equalsIgnoreCase(HttpHeader.Connection) ||
      name.equalsIgnoreCase(HttpHeader.TransferEncoding)

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
        if !isManagedHeader(name) && !name.equalsIgnoreCase(HttpHeader.CacheControl) then
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

  /** The interim response to `Expect: 100-continue`. */
  val Continue100: Array[Byte] = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(
    StandardCharsets.ISO_8859_1
  )

  /** The terminating zero-length chunk that ends a chunked body. */
  val finalChunk: Array[Byte] = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)

  private val CRLF: Array[Byte] = "\r\n".getBytes(StandardCharsets.ISO_8859_1)

end HttpResponseWriter
