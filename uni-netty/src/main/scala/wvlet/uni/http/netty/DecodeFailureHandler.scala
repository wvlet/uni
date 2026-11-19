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

import io.netty.buffer.Unpooled
import io.netty.channel.{
  ChannelDuplexHandler,
  ChannelFutureListener,
  ChannelHandlerContext,
  ChannelPromise
}
import io.netty.handler.codec.DecoderResultProvider
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  HttpHeaderNames,
  HttpRequest,
  HttpHeaderValues,
  HttpResponse,
  HttpResponseStatus,
  HttpStatusClass,
  HttpVersion,
  LastHttpContent,
  TooLongHttpHeaderException,
  TooLongHttpLineException
}
import io.netty.util.ReferenceCountUtil
import wvlet.uni.http.HttpStatus
import wvlet.uni.log.LogSupport

import java.nio.charset.StandardCharsets

/**
  * Answers a request that Netty's HTTP decoder failed on, so no later handler acts on it.
  *
  * Netty never throws on malformed input: it marks the message with a failed `decoderResult()` (a
  * `/bad-request` placeholder, a partly parsed request, or the last chunk of a body) and discards
  * the rest of the connection. Later handlers would act on such a message as a valid one: the
  * request decompressor replaces a failed last chunk with a successful one, and the aggregator can
  * answer `100 Continue` or 413 before it checks the failure. So this handler must sit right after
  * the codec.
  *
  * It drops the message and answers 400 (414 for a long request line, 431 for large headers), then
  * closes the connection. A request whose version is not HTTP/1.0 or HTTP/1.1 is rejected the same
  * way, with 505 (400 for a protocol other than HTTP), as the built-in socket server does: Netty's
  * decoder accepts any `NAME/x.y`. Checking it here, before the aggregator, keeps the status from
  * depending on the body. Earlier pipelined requests are answered first: like
  * [[ConnectionDrainHandler]], it counts requests read minus responses written, and holds the error
  * until that count reaches zero. Writing from here, the error passes only through the codec, never
  * the response compressor, which has not seen the dropped request.
  */
class DecodeFailureHandler extends ChannelDuplexHandler with LogSupport:
  // Only touched on the channel's event loop
  private var pendingRequests     = 0
  private var failure: HttpStatus = null
  private var failureWritten      = false
  private def failed: Boolean     = failure != null

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    if failed then
      ReferenceCountUtil.release(msg)
    else
      msg match
        case m: DecoderResultProvider if m.decoderResult().isFailure =>
          val cause = m.decoderResult().cause()
          ReferenceCountUtil.release(msg)
          debug(s"Rejected a malformed request: ${cause.getMessage}")
          failure = DecodeFailureHandler.statusOf(cause)
          if pendingRequests <= 0 then
            writeFailure(ctx)
        case DecodeFailureHandler.UnsupportedVersion(status) =>
          ReferenceCountUtil.release(msg)
          debug("Rejected a request for an unsupported HTTP version")
          failure = status
          if pendingRequests <= 0 then
            writeFailure(ctx)
        case _ =>
          if msg.isInstanceOf[LastHttpContent] then
            pendingRequests += 1
          ctx.fireChannelRead(msg)

  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit =
    ctx.write(msg, promise)
    // An interim 1xx (e.g. 100 Continue) is not the request's response
    val interim =
      msg match
        case r: HttpResponse =>
          r.status().codeClass() == HttpStatusClass.INFORMATIONAL
        case _ =>
          false
    if msg.isInstanceOf[LastHttpContent] && !interim then
      pendingRequests -= 1
      if failed && pendingRequests <= 0 then
        writeFailure(ctx)

  private def writeFailure(ctx: ChannelHandlerContext): Unit =
    if !failureWritten then
      failureWritten = true
      val response = DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.valueOf(failure.code),
        Unpooled.copiedBuffer(failure.reason, StandardCharsets.UTF_8)
      )
      response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
        .setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
      ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)

end DecodeFailureHandler

object DecodeFailureHandler:
  /**
    * A request for a version the server doesn't speak, and its rejection, as in the built-in
    * server.
    */
  private[netty] object UnsupportedVersion:
    def unapply(msg: Any): Option[HttpStatus] =
      msg match
        case r: HttpRequest =>
          val v = r.protocolVersion()
          if v.protocolName() != "HTTP" then
            Some(HttpStatus.BadRequest_400)
          else if v.majorVersion() != 1 || v.minorVersion() > 1 then
            Some(HttpStatus.HttpVersionNotSupported_505)
          else
            None
        case _ =>
          None

  private[netty] def statusOf(cause: Throwable): HttpStatus =
    cause match
      case _: TooLongHttpLineException =>
        HttpStatus.UriTooLong_414
      case _: TooLongHttpHeaderException =>
        HttpStatus.HeaderFieldsTooLarge_431
      case _ =>
        HttpStatus.BadRequest_400
