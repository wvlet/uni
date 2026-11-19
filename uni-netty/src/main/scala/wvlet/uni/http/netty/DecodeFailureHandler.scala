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

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.DecoderResultProvider
import io.netty.handler.codec.http.{TooLongHttpHeaderException, TooLongHttpLineException}
import io.netty.util.ReferenceCountUtil
import wvlet.uni.http.HttpStatus

/**
  * Stops a request that Netty's HTTP decoder failed on, so no later handler acts on it.
  *
  * Netty never throws on malformed input: it marks the message with a failed `decoderResult()` (a
  * `/bad-request` placeholder, a partly parsed request, or the last chunk of a body) and discards
  * the rest of the connection. Later handlers would act on such a message as a valid one: the
  * request decompressor replaces a failed last chunk with a successful one, and the aggregator can
  * answer `100 Continue` or 413 before it checks the failure. So this handler must sit right after
  * the codec. It drops the message and fires [[DecodeFailure]], which [[NettyRequestHandler]]
  * answers after the requests it already has, then closes the connection.
  */
class DecodeFailureHandler extends ChannelInboundHandlerAdapter:
  // Only touched on the channel's event loop
  private var failed = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    if failed then
      ReferenceCountUtil.release(msg)
    else
      msg match
        case m: DecoderResultProvider if m.decoderResult().isFailure =>
          failed = true
          val cause = m.decoderResult().cause()
          ReferenceCountUtil.release(msg)
          ctx.fireUserEventTriggered(DecodeFailure(cause))
        case _ =>
          ctx.fireChannelRead(msg)

end DecodeFailureHandler

/**
  * A request Netty's HTTP decoder failed on, answered with [[status]] and a closed connection.
  */
case class DecodeFailure(cause: Throwable):
  def status: HttpStatus =
    cause match
      case _: TooLongHttpLineException =>
        HttpStatus.UriTooLong_414
      case _: TooLongHttpHeaderException =>
        HttpStatus.HeaderFieldsTooLarge_431
      case _ =>
        HttpStatus.BadRequest_400
