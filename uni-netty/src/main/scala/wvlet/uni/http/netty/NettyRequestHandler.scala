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
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.{
  DefaultFullHttpResponse,
  DefaultHttpContent,
  DefaultHttpResponse,
  FullHttpRequest,
  HttpHeaderNames,
  HttpHeaderValues,
  HttpResponseStatus,
  HttpUtil,
  HttpVersion,
  LastHttpContent
}
import wvlet.uni.http.{
  ContentType,
  HttpContent,
  HttpHeader,
  HttpMethod,
  HttpMultiMap,
  HttpStatus,
  Request,
  Response,
  ServerSentEvent
}
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.util.concurrent.ThreadPoolExecutor
import scala.jdk.CollectionConverters.*

/**
  * Netty channel handler that processes HTTP requests using RxHttpHandler
  *
  * @param sseExecutor
  *   shared thread pool for SSE stream consumption, managed by NettyHttpServer
  */
class NettyRequestHandler(handler: RxHttpHandler, sseExecutor: ThreadPoolExecutor)
    extends SimpleChannelInboundHandler[FullHttpRequest]
    with LogSupport:

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = ctx.flush()

  override def channelRead0(ctx: ChannelHandlerContext, nettyRequest: FullHttpRequest): Unit =
    val keepAlive = HttpUtil.isKeepAlive(nettyRequest)
    try
      val request  = toUniRequest(nettyRequest)
      val response = handler.handle(request)
      runRxResponse(ctx, response, keepAlive)
    catch
      case e: Exception =>
        warn(s"Error handling request: ${e.getMessage}", e)
        sendResponse(ctx, Response.internalServerError(e.getMessage), keepAlive)

  private def runRxResponse(
      ctx: ChannelHandlerContext,
      rx: Rx[Response],
      keepAlive: Boolean
  ): Unit =
    RxRunner.runOnce(rx) {
      case OnNext(response) =>
        val resp = response.asInstanceOf[Response]
        if resp.isEventStream then
          sendSseResponse(ctx, resp)
        else
          sendResponse(ctx, resp, keepAlive)
      case OnError(e) =>
        warn(s"Error in Rx handler: ${e.getMessage}", e)
        sendResponse(ctx, Response.internalServerError(e.getMessage), keepAlive)
      case OnCompletion =>
        sendResponse(ctx, Response.notFound, keepAlive)
    }

  private def sendSseResponse(ctx: ChannelHandlerContext, response: Response): Unit =
    // Send initial SSE response headers with chunked transfer encoding
    val nettyResponse = DefaultHttpResponse(
      HttpVersion.HTTP_1_1,
      HttpResponseStatus.valueOf(response.status.code)
    )
    nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
    nettyResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
    nettyResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE)
    nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

    // Copy custom headers from response (skip Content-Type as it's already set)
    response
      .headers
      .entries
      .foreach { case (name, value) =>
        if !name.equalsIgnoreCase(HttpHeader.ContentType) then
          nettyResponse.headers().set(name, value)
      }

    // Remove the content compressor for SSE to prevent buffering/encoding issues
    // with chunked streaming responses written from non-event-loop threads.
    // SSE events should not be compressed as it adds latency.
    try
      ctx.pipeline().remove(NettyHttpServer.CompressorHandler)
    catch
      case _: java.util.NoSuchElementException =>
        ()

    ctx.writeAndFlush(nettyResponse)

    // Offload SSE stream consumption to a separate thread pool to avoid blocking
    // the Netty event loop with long-running streams.
    // ctx.writeAndFlush() is thread-safe in Netty and can be called from any thread.
    try
      sseExecutor.execute { () =>
        try
          RxRunner.run(response.events) {
            case OnNext(event) =>
              val sse     = event.asInstanceOf[ServerSentEvent]
              val content = sse.toContent
              val buf     = Unpooled.copiedBuffer(content.getBytes("UTF-8"))
              ctx.writeAndFlush(DefaultHttpContent(buf))
            case OnError(e) =>
              warn(s"SSE stream error: ${e.getMessage}", e)
              if ctx.channel().isActive then
                ctx
                  .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                  .addListener(ChannelFutureListener.CLOSE)
            case OnCompletion =>
              ctx
                .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(ChannelFutureListener.CLOSE)
          }
        catch
          case e: Exception =>
            warn(s"SSE stream processing error: ${e.getMessage}", e)
            if ctx.channel().isActive then
              ctx
                .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(ChannelFutureListener.CLOSE)
      }
    catch
      case e: java.util.concurrent.RejectedExecutionException =>
        warn(s"SSE executor is saturated; closing stream", e)
        ctx
          .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
          .addListener(ChannelFutureListener.CLOSE)
    end try

  end sendSseResponse

  private def sendResponse(
      ctx: ChannelHandlerContext,
      response: Response,
      keepAlive: Boolean
  ): Unit =
    val nettyResponse = toNettyResponse(response)

    if keepAlive then
      nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
      ctx.writeAndFlush(nettyResponse)
    else
      nettyResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
      ctx.writeAndFlush(nettyResponse).addListener(ChannelFutureListener.CLOSE)

  private def toUniRequest(nettyRequest: FullHttpRequest): Request =
    val method = HttpMethod
      .of(nettyRequest.method().name())
      .getOrElse {
        throw IllegalArgumentException(s"Unsupported HTTP method: ${nettyRequest.method().name()}")
      }
    val uri = nettyRequest.uri()

    val headersBuilder = HttpMultiMap.newBuilder
    nettyRequest
      .headers()
      .entries()
      .asScala
      .foreach { entry =>
        headersBuilder.add(entry.getKey, entry.getValue)
      }

    val content =
      val buf = nettyRequest.content()
      if buf.readableBytes() > 0 then
        val bytes = new Array[Byte](buf.readableBytes())
        buf.readBytes(bytes)
        val ct = headersBuilder
          .result()
          .get(HttpHeader.ContentType)
          .flatMap(ContentType.parse)
          .getOrElse(ContentType.ApplicationOctetStream)
        HttpContent.bytes(bytes, ct)
      else
        HttpContent.Empty

    Request(method = method, uri = uri, headers = headersBuilder.result(), content = content)

  end toUniRequest

  private def toNettyResponse(response: Response): DefaultFullHttpResponse =
    val status = HttpResponseStatus.valueOf(response.status.code)

    val content      = response.content
    val contentBytes = content.toContentBytes
    val buf          = Unpooled.wrappedBuffer(contentBytes)

    val nettyResponse = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf)

    response
      .headers
      .entries
      .foreach { case (name, value) =>
        nettyResponse.headers().add(name, value)
      }

    content
      .contentType
      .foreach { ct =>
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, ct.value)
      }

    nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.length)

    nettyResponse

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
    if NettyRequestHandler.isBenignIOException(cause) then
      debug(s"Benign I/O exception: ${cause.getMessage}")
    else
      warn(s"Channel exception: ${cause.getMessage}", cause)
    ctx.close()

end NettyRequestHandler

object NettyRequestHandler:

  def apply(handler: RxHttpHandler, sseExecutor: ThreadPoolExecutor): NettyRequestHandler =
    new NettyRequestHandler(handler, sseExecutor)

  // "Connection reset" also matches "Connection reset by peer" via contains check
  private val benignIOExceptionMessages = Set("Connection reset", "Broken pipe")

  /**
    * Check if the exception is a benign I/O error that commonly occurs during normal operations,
    * such as client disconnections or network interruptions. These exceptions should be logged at
    * DEBUG level. This method traverses the entire exception cause chain.
    */
  def isBenignIOException(cause: Throwable): Boolean =
    @scala.annotation.tailrec
    def loop(ex: Throwable): Boolean =
      if ex == null then
        false
      else
        ex match
          case _: io.netty.handler.codec.PrematureChannelClosureException =>
            true
          case _: java.nio.channels.ClosedChannelException =>
            true
          case e: java.io.IOException =>
            val msg = e.getMessage
            if msg != null && benignIOExceptionMessages.exists(m => msg.contains(m)) then
              true
            else
              loop(ex.getCause)
          case _ =>
            loop(ex.getCause)
    loop(cause)

end NettyRequestHandler
