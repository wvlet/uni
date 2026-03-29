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

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption, EventLoopGroup}
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.{
  HttpContentCompressor,
  HttpObjectAggregator,
  HttpServerCodec,
  HttpServerExpectContinueHandler,
  HttpServerKeepAliveHandler
}
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import wvlet.uni.log.LogSupport
import wvlet.uni.util.ThreadUtil

import java.net.InetSocketAddress
import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

/**
  * Netty-based HTTP server implementation
  */
class NettyHttpServer(config: NettyServerConfig) extends LogSupport:
  private var bossGroup: EventLoopGroup                               = null
  private var workerGroup: EventLoopGroup                             = null
  private var handlerExecutorGroup: Option[DefaultEventExecutorGroup] = None
  private var channel: Channel                                        = null

  // Shared SSE executor across all connections
  private val sseExecutor = ThreadPoolExecutor(
    0,
    config.sseMaxThreads,
    60L,
    TimeUnit.SECONDS,
    SynchronousQueue[Runnable](),
    ThreadUtil.newDaemonThreadFactory(s"${config.name}-sse")
  )

  private val started = AtomicBoolean(false)
  private val stopped = AtomicBoolean(false)

  @volatile
  private var shutdownHook: Option[Thread] = None

  private def effectiveHandler: RxHttpHandler =
    if config.filters.isEmpty then
      config.handler
    else
      val chained = RxHttpFilter.chain(config.filters)
      RxHttpHandler { request =>
        chained.apply(request, config.handler)
      }

  def start(): Unit =
    if stopped.get() then
      throw IllegalStateException("Server is already stopped")

    if !started.compareAndSet(false, true) then
      throw IllegalStateException("Server is already running")

    val useEpoll = config.useNativeTransport && Epoll.isAvailable

    if useEpoll then
      bossGroup = EpollEventLoopGroup(1, ThreadUtil.newDaemonThreadFactory(s"${config.name}-boss"))
      workerGroup = EpollEventLoopGroup(
        0,
        ThreadUtil.newDaemonThreadFactory(s"${config.name}-worker")
      )
    else
      bossGroup = NioEventLoopGroup(1, ThreadUtil.newDaemonThreadFactory(s"${config.name}-boss"))
      workerGroup = NioEventLoopGroup(
        0,
        ThreadUtil.newDaemonThreadFactory(s"${config.name}-worker")
      )

    handlerExecutorGroup = config
      .handlerExecutorThreads
      .map { threads =>
        DefaultEventExecutorGroup(
          threads,
          ThreadUtil.newDaemonThreadFactory(s"${config.name}-handler")
        )
      }

    val handler = effectiveHandler

    val bootstrap = ServerBootstrap()
    bootstrap
      .group(bossGroup, workerGroup)
      .channel(
        if useEpoll then
          classOf[EpollServerSocketChannel]
        else
          classOf[NioServerSocketChannel]
      )
      .childHandler(
        new ChannelInitializer[SocketChannel]:
          override def initChannel(ch: SocketChannel): Unit =
            val pipeline = ch.pipeline()
            pipeline.addLast(
              "codec",
              HttpServerCodec(
                config.maxInitialLineLength,
                config.maxHeaderSize,
                config.maxContentLength
              )
            )
            pipeline.addLast("keepAlive", HttpServerKeepAliveHandler())
            pipeline.addLast("aggregator", HttpObjectAggregator(config.maxContentLength))
            pipeline.addLast(NettyHttpServer.CompressorHandler, HttpContentCompressor())
            pipeline.addLast("expectContinue", HttpServerExpectContinueHandler())
            pipeline.addLast("chunkedWriter", ChunkedWriteHandler())
            val reqHandler = NettyRequestHandler(handler, sseExecutor)
            handlerExecutorGroup match
              case Some(executor) =>
                pipeline.addLast(executor, "handler", reqHandler)
              case None =>
                pipeline.addLast("handler", reqHandler)
      )
      .option(ChannelOption.SO_BACKLOG, Integer.valueOf(128))
      .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)

    val bindFuture = bootstrap.bind(config.host, config.port).sync()
    channel = bindFuture.channel()

    registerShutdownHookIfNeeded()
    debug(s"Netty server started at ${localAddress}")

  end start

  def stop(): Unit =
    if !stopped.compareAndSet(false, true) then
      return

    debug(
      s"Stopping Netty server at ${localAddress} " +
        s"(quietPeriod=${config.shutdownQuietPeriodSeconds}s, timeout=${config
            .shutdownTimeoutSeconds}s)"
    )

    unregisterShutdownHook()

    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.shutdownTimeoutSeconds)
    def remainingSeconds: Long = math.max(
      0,
      TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime())
    )
    def remainingMillis: Long = math.max(
      0,
      TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
    )
    def effectiveQuietPeriod: Long = math.min(config.shutdownQuietPeriodSeconds, remainingSeconds)

    // Close the server channel first to stop accepting new connections
    if channel != null then
      channel.close().await(remainingMillis, TimeUnit.MILLISECONDS)

    // Drain handler executor group first so in-flight handlers can still
    // flush responses through the event loop
    handlerExecutorGroup.foreach { group =>
      group
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)
    }

    // Shutdown SSE executor
    sseExecutor.shutdown()
    sseExecutor.awaitTermination(remainingMillis, TimeUnit.MILLISECONDS)

    // Then shutdown worker group to complete remaining I/O
    if workerGroup != null then
      workerGroup
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)

    // Then shutdown boss group
    if bossGroup != null then
      bossGroup
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)

    debug(s"Netty server stopped at ${localAddress}")

  end stop

  private def registerShutdownHookIfNeeded(): Unit =
    if config.registerShutdownHook then
      val hook = Thread(
        () =>
          debug(s"Received shutdown signal for ${config.name} server")
          stop()
        ,
        s"${config.name}-shutdown"
      )
      shutdownHook = Some(hook)
      Runtime.getRuntime.addShutdownHook(hook)
      debug(s"Registered shutdown hook for ${config.name} server")

  private def unregisterShutdownHook(): Unit = shutdownHook.foreach { hook =>
    try
      Runtime.getRuntime.removeShutdownHook(hook)
    catch
      case _: IllegalStateException =>
        // JVM is already shutting down, hook cannot be removed
        ()
    shutdownHook = None
  }

  def awaitTermination(): Unit =
    if channel != null then
      channel.closeFuture().sync()

  def isRunning: Boolean = started.get() && !stopped.get()

  def localAddress: InetSocketAddress =
    if channel != null then
      channel.localAddress().asInstanceOf[InetSocketAddress]
    else
      InetSocketAddress(config.host, config.port)

  def localPort: Int = localAddress.getPort

end NettyHttpServer

object NettyHttpServer:
  private[netty] val CompressorHandler = "compressor"

  def apply(config: NettyServerConfig): NettyHttpServer = new NettyHttpServer(config)
