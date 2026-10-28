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
import io.netty.channel.{
  Channel,
  ChannelInitializer,
  ChannelOption,
  EventLoopGroup,
  IoHandlerFactory,
  MultiThreadIoEventLoopGroup
}
import io.netty.channel.epoll.{Epoll, EpollIoHandler, EpollServerSocketChannel}
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioIoHandler
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
import io.netty.util.concurrent.{DefaultEventExecutorGroup, EventExecutorGroup, GlobalEventExecutor}
import wvlet.uni.http.{ConnectionLimiter, HttpServer}
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.util.ThreadUtil

import java.net.InetSocketAddress
import java.util.concurrent.{ExecutorService, SynchronousQueue, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

/**
  * Netty-based HTTP server implementation
  */
class NettyHttpServer(config: NettyServerConfig) extends HttpServer with LogSupport:
  private var bossGroup: EventLoopGroup                               = null
  private var workerGroup: EventLoopGroup                             = null
  private var handlerExecutorGroup: Option[DefaultEventExecutorGroup] = None
  private var channel: Channel                                        = null
  // Accepted connections; a channel leaves the group when it closes
  private val connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)

  // Caps open SSE streams across all connections; a stream over the limit gets 503
  private val streamLimiter = ConnectionLimiter(config.maxStreamingConnections)

  // Shared thread pool for SSE stream consumption across all connections. Threads are created on
  // demand, at most one per stream slot, and reclaimed after 60s idle.
  private val sseExecutor: ExecutorService = ThreadPoolExecutor(
    0,
    config.maxStreamingConnections,
    60L,
    TimeUnit.SECONDS,
    SynchronousQueue[Runnable](),
    ThreadUtil.newDaemonThreadFactory(s"${config.name}-sse")
  )

  private val started = AtomicBoolean(false)
  private val stopped = AtomicBoolean(false)

  @volatile
  private var shutdownHook: Option[Thread] = None

  def start(): Unit =
    if stopped.get() then
      throw IllegalStateException("Server is already stopped")

    if !started.compareAndSet(false, true) then
      throw IllegalStateException("Server is already running")

    val useEpoll = config.useNativeTransport && Epoll.isAvailable

    val ioHandlerFactory: IoHandlerFactory =
      if useEpoll then
        EpollIoHandler.newFactory()
      else
        NioIoHandler.newFactory()
    bossGroup = MultiThreadIoEventLoopGroup(
      1,
      ThreadUtil.newDaemonThreadFactory(s"${config.name}-boss"),
      ioHandlerFactory
    )
    workerGroup = MultiThreadIoEventLoopGroup(
      0,
      ThreadUtil.newDaemonThreadFactory(s"${config.name}-worker"),
      ioHandlerFactory
    )

    handlerExecutorGroup = config
      .handlerExecutorThreads
      .map { threads =>
        DefaultEventExecutorGroup(
          threads,
          ThreadUtil.newDaemonThreadFactory(s"${config.name}-handler")
        )
      }

    val handler = config.effectiveHandler

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
            connections.add(ch)
            val pipeline = ch.pipeline()
            pipeline.addLast(
              "codec",
              HttpServerCodec(
                config.maxInitialLineLength,
                config.maxHeaderSize,
                config.maxContentLength
              )
            )
            pipeline.addLast("drain", ConnectionDrainHandler())
            if config.requestReadTimeoutMillis > 0 then
              pipeline.addLast(
                NettyHttpServer.RequestReadTimeoutHandlerName,
                RequestReadTimeoutHandler(config.requestReadTimeoutMillis.toLong)
              )
            pipeline.addLast("keepAlive", HttpServerKeepAliveHandler())
            if config.requestDecompression then
              val decompression = NettyRequestDecompression(config.maxDecompressedRequestSize)
              pipeline.addLast("requestDecompressor", decompression.decompressor)
              pipeline.addLast("requestDecompressionLimiter", decompression.limiter)
            pipeline.addLast("aggregator", HttpObjectAggregator(config.maxContentLength))
            pipeline.addLast(NettyHttpServer.CompressorHandler, HttpContentCompressor())
            pipeline.addLast("expectContinue", HttpServerExpectContinueHandler())
            pipeline.addLast("chunkedWriter", ChunkedWriteHandler())
            val reqHandler = NettyRequestHandler(
              handler,
              sseExecutor,
              config.webSocketRoutes,
              config.webSocketMaxFrameSize,
              config.webSocketPingIntervalMillis,
              handlerExecutorGroup,
              streamLimiter,
              config.allowedOrigins,
              config.effectiveAllowedHosts,
              config.detailedErrors
            )
            handlerExecutorGroup match
              case Some(executor) =>
                // Netty 4.2 deprecates per-handler executors without a drop-in replacement; keep them
                // so blocking handlers stay off the event loop.
                pipeline.addLast(executor, "handler", reqHandler): @nowarn("cat=deprecation")
              case None =>
                pipeline.addLast("handler", reqHandler)
          end initChannel
      )
      .option(ChannelOption.SO_BACKLOG, Integer.valueOf(128))
      .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)

    val bindFuture = bootstrap.bind(config.host, config.port).sync()
    channel = bindFuture.channel()

    registerShutdownHookIfNeeded()
    debug(s"Netty server started at ${localAddress}")

  end start

  override def stop(): Unit =
    if !stopped.compareAndSet(false, true) then
      return
    if onServerThread then
      // stop() waits for work this thread has to do (its own request or event loop), so shut down
      // from another thread
      Thread(() => shutdown(), s"${config.name}-stop").start()
    else
      shutdown()

  private def onServerThread: Boolean =
    def inGroup(group: EventExecutorGroup): Boolean =
      group != null && group.iterator().asScala.exists(_.inEventLoop())
    inGroup(workerGroup) || inGroup(bossGroup) || handlerExecutorGroup.exists(inGroup)

  private def shutdown(): Unit =
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
    Option(channel).foreach(_.close().await(remainingMillis, TimeUnit.MILLISECONDS))

    // Close accepted connections while the executors their handlers run on are still alive
    closeConnections(remainingMillis)

    // Then drain the handler executor group, before the event loops its handlers write through
    handlerExecutorGroup.foreach { group =>
      group
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)
    }

    // Shutdown SSE executor
    sseExecutor.shutdown()
    sseExecutor.awaitTermination(remainingMillis, TimeUnit.MILLISECONDS)

    // Then shutdown worker group to complete remaining I/O
    Option(workerGroup).foreach { group =>
      group
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)
    }

    // Then shutdown boss group
    Option(bossGroup).foreach { group =>
      group
        .shutdownGracefully(effectiveQuietPeriod, remainingSeconds, TimeUnit.SECONDS)
        .await(remainingMillis, TimeUnit.MILLISECONDS)
    }

    debug(s"Netty server stopped at ${localAddress}")

  end shutdown

  /**
    * Close idle connections now and busy ones after their pending responses, then force-close any
    * still open at the deadline (e.g., an endless SSE stream).
    */
  private def closeConnections(remainingMillis: => Long): Unit =
    // Past the deadline, still give the forced close a moment instead of waiting forever on an
    // event loop that a blocked handler holds
    def boundedMillis: Long = math.max(remainingMillis, 1000L)
    val allClosed           = connections.newCloseFuture()
    connections.forEach { ch =>
      Option(ch.pipeline().get(classOf[ConnectionDrainHandler])).foreach { h =>
        ch.eventLoop().execute(() => h.drain())
      }
    }
    if !allClosed.await(remainingMillis, TimeUnit.MILLISECONDS) then
      debug(s"Closing ${connections.size()} connections still busy at the shutdown deadline")
      connections.close().await(boundedMillis, TimeUnit.MILLISECONDS)
    // A closed channel fires channelInactive in a later event loop task; let those run, so the
    // events reach the handler executor before it shuts down
    Option(workerGroup).foreach { group =>
      group.forEach(_.submit((() => ()): Runnable).await(boundedMillis, TimeUnit.MILLISECONDS))
    }

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

  override def awaitTermination(): Unit =
    if channel != null then
      channel.closeFuture().sync()

  override def isRunning: Boolean = started.get() && !stopped.get()

  // Netty binds synchronously in start() (bootstrap.bind(...).sync()), so the server is ready as
  // soon as this instance is returned.
  override def whenReady: Rx[HttpServer] = Rx.single(this)

  /**
    * The bound socket address. JVM-specific accessor exposing the underlying `InetSocketAddress`.
    */
  def localSocketAddress: InetSocketAddress =
    if channel != null then
      channel.localAddress().asInstanceOf[InetSocketAddress]
    else
      InetSocketAddress(config.host, config.port)

  override def localAddress: String =
    val addr = localSocketAddress
    s"${addr.getHostString}:${addr.getPort}"

  override def localPort: Int = localSocketAddress.getPort

end NettyHttpServer

object NettyHttpServer:
  private[netty] val CompressorHandler             = "compressor"
  private[netty] val RequestReadTimeoutHandlerName = "requestReadTimeout"
  private[netty] val WebSocketHandlerName          = "wsHandler"

  def apply(config: NettyServerConfig): NettyHttpServer = new NettyHttpServer(config)
