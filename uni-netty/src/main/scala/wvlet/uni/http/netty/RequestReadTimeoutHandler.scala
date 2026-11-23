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

import io.netty.channel.{
  ChannelDuplexHandler,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelPromise
}
import io.netty.handler.codec.http.LastHttpContent
import wvlet.uni.log.LogSupport

import java.util.concurrent.{ScheduledFuture, TimeUnit}

/**
  * Closes a connection that does not deliver a complete HTTP request within `timeoutMillis`.
  *
  * The clock starts when the connection opens and again after each response is written, and stops
  * once the request's last content arrives. This bounds both slow senders (slowloris trickling
  * header bytes) and idle keep-alive connections, without limiting how long a handler may take to
  * produce its response. Must sit after the HTTP codec so it sees decoded request and response
  * objects; it is removed when a connection upgrades to WebSocket.
  *
  * With a positive `idleTimeoutMillis`, the wait for a request's first bytes gets its own clock, as
  * on the built-in server: a connection waiting for its next request closes after
  * `idleTimeoutMillis`, and the request clock (`timeoutMillis`, 0 for none) starts when the first
  * bytes arrive, as [[arrivalProbe]] reports from in front of the codec.
  */
class RequestReadTimeoutHandler(timeoutMillis: Long, idleTimeoutMillis: Long = 0)
    extends ChannelDuplexHandler
    with LogSupport:
  // Only touched on the channel's event loop
  private var timer: ScheduledFuture[?] = null
  // Whether the armed clock is the idle one, waiting for a request's first bytes
  private var waitingForRequest          = false
  private var ctx: ChannelHandlerContext = null

  /** A handler for the front of the pipeline that reports raw bytes before the codec sees them. */
  def arrivalProbe: ChannelInboundHandlerAdapter =
    new ChannelInboundHandlerAdapter:
      override def channelRead(probeCtx: ChannelHandlerContext, msg: Any): Unit =
        bytesArrived()
        probeCtx.fireChannelRead(msg)

  private def bytesArrived(): Unit =
    if waitingForRequest && ctx != null then
      waitingForRequest = false
      if timeoutMillis > 0 then
        schedule(ctx, timeoutMillis)
      else
        disarm()

  // Requests received minus responses written. Pipelining can make it exceed 1, and it can dip
  // below 0 when a response precedes its request's end (e.g. the aggregator's early 413 for an
  // oversized body). The connection is waiting for a request whenever it is <= 0.
  private var pendingRequests = 0

  override def handlerAdded(ctx: ChannelHandlerContext): Unit =
    this.ctx = ctx
    if ctx.channel().isActive then
      arm(ctx)

  override def channelActive(ctx: ChannelHandlerContext): Unit =
    arm(ctx)
    ctx.fireChannelActive()

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    disarm()
    ctx.fireChannelInactive()

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit =
    disarm()
    this.ctx = null

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    if msg.isInstanceOf[LastHttpContent] then
      pendingRequests += 1
      if pendingRequests > 0 then
        disarm()
      else
        // Already answered: restart the clock for the next request
        arm(ctx)
    ctx.fireChannelRead(msg)

  override def write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise): Unit =
    if msg.isInstanceOf[LastHttpContent] then
      pendingRequests -= 1
      // Once every received request is answered, wait for the next one
      if pendingRequests <= 0 then
        if promise.isVoid then
          arm(ctx)
        else
          promise.addListener { (f: io.netty.util.concurrent.Future[?]) =>
            if f.isSuccess && pendingRequests <= 0 && ctx.channel().isActive && !ctx.isRemoved then
              arm(ctx)
          }
    ctx.write(msg, promise)

  // Wait for the next request: on the idle clock when it is set, otherwise on the request clock
  private def arm(ctx: ChannelHandlerContext): Unit =
    if idleTimeoutMillis > 0 then
      schedule(ctx, idleTimeoutMillis)
      waitingForRequest = true
    else
      schedule(ctx, timeoutMillis)

  private def schedule(ctx: ChannelHandlerContext, millis: Long): Unit =
    disarm()
    timer = ctx
      .executor()
      .schedule(
        () =>
          debug(s"Closing ${ctx.channel().remoteAddress()}: no complete request in ${millis}ms")
          ctx.close()
        ,
        millis,
        TimeUnit.MILLISECONDS
      )

  private def disarm(): Unit =
    waitingForRequest = false
    if timer != null then
      timer.cancel(false)
      timer = null

end RequestReadTimeoutHandler
