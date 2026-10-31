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

import wvlet.uni.http.ServerOptions

/**
  * Netty-only options for `Http.server`, created with [[Netty.options]]. Each setter is checked
  * when called, and the options are applied to the `NettyServerConfig` built from `Http.server`'s
  * settings.
  */
case class NettyOptions private (updates: Seq[NettyServerConfig => NettyServerConfig])
    extends ServerOptions:

  // Run the update on a default config now, so invalid values fail at the call site
  private def add(update: NettyServerConfig => NettyServerConfig): NettyOptions =
    update(NettyServerConfig())
    NettyOptions(updates :+ update)

  private[netty] def applyTo(config: NettyServerConfig): NettyServerConfig =
    updates.foldLeft(config)((c, update) => update(c))

  def withMaxInitialLineLength(length: Int): NettyOptions = add(_.withMaxInitialLineLength(length))
  def withMaxHeaderSize(size: Int): NettyOptions          = add(_.withMaxHeaderSize(size))
  def withUseNativeTransport(enabled: Boolean): NettyOptions = add(
    _.withUseNativeTransport(enabled)
  )

  def noNativeTransport: NettyOptions                      = add(_.noNativeTransport)
  def withShutdownQuietPeriod(seconds: Long): NettyOptions = add(_.withShutdownQuietPeriod(seconds))
  def withShutdownTimeout(seconds: Long): NettyOptions     = add(_.withShutdownTimeout(seconds))

  def withGracefulShutdown(quietPeriodSeconds: Long = 2, timeoutSeconds: Long = 30): NettyOptions =
    add(_.withGracefulShutdown(quietPeriodSeconds, timeoutSeconds))

  def withShutdownHook: NettyOptions = add(_.withShutdownHook)
  def noShutdownHook: NettyOptions   = add(_.noShutdownHook)

  def withHandlerExecutorThreads(threads: Int): NettyOptions = add(
    _.withHandlerExecutorThreads(threads)
  )

  def withRequestReadTimeoutMillis(millis: Int): NettyOptions = add(
    _.withRequestReadTimeoutMillis(millis)
  )

  def noRequestReadTimeout: NettyOptions     = add(_.noRequestReadTimeout)
  def withRequestDecompression: NettyOptions = add(_.withRequestDecompression)
  def noRequestDecompression: NettyOptions   = add(_.noRequestDecompression)

  def withMaxDecompressedRequestSize(sizeInBytes: Int): NettyOptions = add(
    _.withMaxDecompressedRequestSize(sizeInBytes)
  )

  def withMaxStreamingConnections(n: Int): NettyOptions = add(_.withMaxStreamingConnections(n))

end NettyOptions

object NettyOptions:
  private[netty] val empty: NettyOptions = NettyOptions(Nil)
