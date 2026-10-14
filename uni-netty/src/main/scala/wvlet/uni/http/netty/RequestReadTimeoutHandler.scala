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

import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
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
  */
class RequestReadTimeoutHandler(timeoutMillis: Long) extends ChannelDuplexHandler with LogSupport:
  // Only touched on the channel's event loop
  private var timer: ScheduledFuture[?] = null
  // Requests received minus responses written. Pipelining can make it exceed 1, and it can dip
  // below 0 when a response precedes its request's end (e.g. the aggregator's early 413 for an
  // oversized body). The connection is waiting for a request whenever it is <= 0.
  private var pendingRequests = 0

  override def handlerAdded(ctx: ChannelHandlerContext): Unit =
    if ctx.channel().isActive then
      arm(ctx)

  override def channelActive(ctx: ChannelHandlerContext): Unit =
    arm(ctx)
    ctx.fireChannelActive()

  override def channelInactive(ctx: ChannelHandlerContext): Unit =
    disarm()
    ctx.fireChannelInactive()

  override def handlerRemoved(ctx: ChannelHandlerContext): Unit = disarm()

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

  private def arm(ctx: ChannelHandlerContext): Unit =
    disarm()
    timer = ctx
      .executor()
      .schedule(
        () =>
          debug(
            s"Closing ${ctx.channel().remoteAddress()}: no complete request in ${timeoutMillis}ms"
          )
          ctx.close()
        ,
        timeoutMillis,
        TimeUnit.MILLISECONDS
      )

  private def disarm(): Unit =
    if timer != null then
      timer.cancel(false)
      timer = null

end RequestReadTimeoutHandler
