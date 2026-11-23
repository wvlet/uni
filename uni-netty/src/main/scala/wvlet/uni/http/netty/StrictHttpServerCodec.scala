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
package wvlet.uni.http.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.{
  HttpDecoderConfig,
  HttpHeaderNames,
  HttpMethod,
  HttpRequest,
  HttpRequestDecoder,
  HttpResponse,
  HttpResponseEncoder,
  HttpStatusClass,
  LastHttpContent
}
import io.netty.util.ReferenceCountUtil

/**
  * Netty's `HttpServerCodec`, split into its decoder and encoder, with a strict request line.
  *
  * Netty 4.2 splits the request line on any run of SP, HTAB, VT, FF, or bare CR, and skips any run
  * of CR and LF before the method, a bare CR included; `HttpDecoderConfig` has no option to turn
  * that off. A proxy that reads `GET\r /a HTTP/1.1` or `\rGET /a HTTP/1.1` differently would see
  * different requests (request smuggling). The decoder here allows exactly one SP between the
  * words, as RFC 9112 Section 3 does, and only empty lines (CRLF, or a bare LF) before the request
  * line, as RFC 9112 Section 2.2 does, both as the built-in socket server does. A violation fails
  * the request through Netty's own invalid-message path, so [[DecodeFailureHandler]] answers 400
  * and closes the connection after the responses to earlier pipelined requests.
  *
  * `HttpServerCodec` is final and its decoder private, so this rebuilds what it adds on top of the
  * plain decoder and encoder: the queue of request methods (no body in a response to `HEAD`, no
  * `Transfer-Encoding` in a 2xx to `CONNECT`), and a cap on requests awaiting a response. Add
  * [[decoder]] and [[encoder]] as two handlers: Netty's WebSocket handshake looks them up by class.
  * Both must run on the channel's event loop.
  */
private[netty] class StrictHttpServerCodec(
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int,
    maxPipelineDepth: Int = StrictHttpServerCodec.DefaultMaxPipelineDepth
):
  // Methods of the requests decoded but not yet answered, oldest first
  private val methods                = java.util.ArrayDeque[HttpMethod]()
  private var mustCloseAfterResponse = false

  private val config = HttpDecoderConfig()
    .setMaxInitialLineLength(maxInitialLineLength)
    .setMaxHeaderSize(maxHeaderSize)
    .setMaxChunkSize(maxChunkSize)
    // The defaults, set so a JVM system property can't loosen them: reject a request with both
    // Transfer-Encoding and Content-Length, and lines that don't end with CRLF
    .setUseRfc9112TransferEncoding(true)
    .setStrictLineParsing(true)

  val decoder: HttpRequestDecoder  = RequestDecoder()
  val encoder: HttpResponseEncoder = ResponseEncoder()

  private class RequestDecoder extends HttpRequestDecoder(config):
    private var discard = false
    // Whether the next bytes come before a request line, where Netty would skip a bare CR
    private var atRequestStart = true
    private var methodEnd      = 0
    private var targetEnd      = 0

    override protected def decode(
        ctx: ChannelHandlerContext,
        buffer: ByteBuf,
        out: java.util.List[Object]
    ): Unit =
      if discard then
        buffer.skipBytes(buffer.readableBytes())
      else
        val oldSize = out.size()
        if !atRequestStart || skipEmptyLines(buffer, out) then
          super.decode(ctx, buffer, out)
        var i = oldSize
        while i < out.size() do
          if out.get(i).isInstanceOf[LastHttpContent] then
            atRequestStart = true
          out.get(i) match
            case r: HttpRequest =>
              if methods.size() >= maxPipelineDepth then
                // Too many requests awaiting a response: drop the rest, and close the connection
                // after the next response, as HttpServerCodec does
                mustCloseAfterResponse = true
                discard = true
                var j = i
                while j < out.size() do
                  ReferenceCountUtil.release(out.get(j))
                  j += 1
                out.subList(i, out.size()).clear()
                throw IllegalStateException(s"maxPipelineDepth exceeded: ${maxPipelineDepth}")
              methods.add(r.method())
            case _ =>
          i += 1

    // Netty resets the decoder without a LastHttpContent when the aggregator rejects an Expect
    // request and keeps the connection open
    override def reset(): Unit =
      atRequestStart = true
      super.reset()

    /**
      * Skip the empty lines (CRLF, or a bare LF) before a request line, and return whether its
      * first byte is buffered. A CR followed by another byte fails the request. A CR that is the
      * last byte buffered stays, until the byte after it shows whether it ends an empty line.
      */
    private def skipEmptyLines(buffer: ByteBuf, out: java.util.List[Object]): Boolean =
      val end   = buffer.writerIndex()
      var i     = buffer.readerIndex()
      var found = false
      var stop  = false
      while !stop && i < end do
        val b = buffer.getByte(i)
        if b == '\n' then
          i += 1
        else if b != '\r' then
          found = true
          stop = true
        else if i + 1 == end then
          stop = true
        else if buffer.getByte(i + 1) == '\n' then
          i += 2
        else
          // As Netty fails a request: a /bad-request placeholder, and discard the rest
          val invalid = createInvalidMessage()
          invalid.setDecoderResult(
            DecoderResult.failure(IllegalArgumentException("A bare CR before the request line"))
          )
          out.add(invalid)
          discard = true
          i = end
          stop = true
      buffer.readerIndex(i)
      if found then
        atRequestStart = false
      found

    end skipEmptyLines

    // The words of the request line, located by Netty's lenient split. `bytes` holds the line
    // from offset 0: Netty copies each line into its unpooled scratch buffer before splitting it.
    override protected def splitFirstWordInitialLine(
        bytes: Array[Byte],
        start: Int,
        length: Int
    ): String =
      if start != 0 then
        throw IllegalArgumentException("Whitespace before the method in the request line")
      methodEnd = start + length
      super.splitFirstWordInitialLine(bytes, start, length)

    override protected def splitSecondWordInitialLine(
        bytes: Array[Byte],
        start: Int,
        length: Int
    ): String =
      requireSingleSp(bytes, methodEnd, start)
      targetEnd = start + length
      super.splitSecondWordInitialLine(bytes, start, length)

    // Not called for a line without a version; Netty rejects the empty version then
    override protected def splitThirdWordInitialLine(
        bytes: Array[Byte],
        start: Int,
        length: Int
    ): String =
      requireSingleSp(bytes, targetEnd, start)
      super.splitThirdWordInitialLine(bytes, start, length)

    private def requireSingleSp(bytes: Array[Byte], prevEnd: Int, start: Int): Unit =
      if start != prevEnd + 1 || bytes(prevEnd) != ' ' then
        throw IllegalArgumentException("The words of the request line must be separated by one SP")

  end RequestDecoder

  private class ResponseEncoder extends HttpResponseEncoder:
    private var method: HttpMethod = null

    override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit =
      val p =
        if mustCloseAfterResponse && msg.isInstanceOf[LastHttpContent] then
          mustCloseAfterResponse = false
          promise.unvoid().addListener(ChannelFutureListener.CLOSE)
        else
          promise
      super.write(ctx, msg, p)

    override protected def sanitizeHeadersBeforeEncode(
        msg: HttpResponse,
        isAlwaysEmpty: Boolean
    ): Unit =
      if !isAlwaysEmpty && method == HttpMethod.CONNECT &&
        msg.status().codeClass() == HttpStatusClass.SUCCESS
      then
        // RFC 9110 Section 9.3.6: a 2xx to CONNECT has no body, so no Transfer-Encoding
        msg.headers().remove(HttpHeaderNames.TRANSFER_ENCODING)
      else
        super.sanitizeHeadersBeforeEncode(msg, isAlwaysEmpty)

    override protected def isContentAlwaysEmpty(msg: HttpResponse): Boolean =
      if msg.status().codeClass() == HttpStatusClass.INFORMATIONAL then
        // An interim response doesn't answer the request
        super.isContentAlwaysEmpty(msg)
      else
        method = methods.poll()
        method == HttpMethod.HEAD || super.isContentAlwaysEmpty(msg)

  end ResponseEncoder

end StrictHttpServerCodec

object StrictHttpServerCodec:
  /** As in `HttpServerCodec` */
  val DefaultMaxPipelineDepth = 128
