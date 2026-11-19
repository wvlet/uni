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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Which side of a [[WebSocketRelay]] session closed it first. */
enum WebSocketRelaySide:
  /** The client connected to this server. */
  case Client

  /** The upstream server the relay connected to. */
  case Upstream

  /** The relay itself: the upstream couldn't be reached, or the pre-open queue overflowed. */
  case Relay

/**
  * Usage of one [[WebSocketRelay]] session, reported once when the client connection closes.
  *
  * @param durationMillis
  *   wall-clock time from the client's open to its close
  * @param framesToUpstream
  *   client frames sent to the upstream (text and binary)
  * @param bytesToUpstream
  *   payload bytes of those frames (text as UTF-8)
  * @param framesToClient
  *   upstream frames sent to the client
  * @param bytesToClient
  *   payload bytes of those frames
  * @param statusCode
  *   the close code of the side that closed first, as that side reported it (the upstream's own
  *   code, before it is mapped for the client)
  * @param reason
  *   the close reason that goes with `statusCode`
  * @param closedBy
  *   the side that closed first
  */
case class WebSocketRelayStats(
    durationMillis: Long,
    framesToUpstream: Long,
    bytesToUpstream: Long,
    framesToClient: Long,
    bytesToClient: Long,
    statusCode: Int,
    reason: String,
    closedBy: WebSocketRelaySide
)

/**
  * Relays a WebSocket session to an upstream WebSocket server, piping text and binary frames both
  * ways, for example from a voice client to a model's realtime API. Use [[newHandler]] as a route's
  * handler; the route filter still decides who may connect, and the stats reporter is where an app
  * meters the session.
  *
  * {{{
  * HttpServer.config.withWebSocketRoute(
  *   WebSocketRoute("/realtime", _ =>
  *     WebSocketRelay(Request.get("wss://api.example.com/realtime").withBearerToken(apiKey))
  *       .withStatsReporter(stats => meter(stats))
  *       .newHandler(),
  *     authFilter
  *   ).withCloseOnRejection()
  * )
  * }}}
  *
  * Per session:
  *   - The upstream is connected when the client opens. Client frames that arrive before it opens
  *     are queued, up to [[maxPendingBytes]]; past it the client is closed with 1013.
  *   - If the upstream connect or handshake fails, the client is closed with 4502, with the
  *     upstream's HTTP status in the reason when there was one ("upstream status 401").
  *   - Either side closing closes the other. An upstream code is sent to the client unchanged when
  *     it is 1000, 1001, or 3000–4999. Other codes, which a browser-facing client can't be given as
  *     app codes, map into 4900–4999: 1002–1099 become 4902–4999 (1006, the upstream dropping,
  *     becomes 4906; 1011 becomes 4911), and anything else becomes 4900. A client that dropped
  *     (1006) closes the upstream with 1001.
  *   - An error on either side closes that side with 1011, which then closes the other.
  *
  * @param upstream
  *   the upstream `ws://` or `wss://` request, with the headers (e.g. `Authorization`) to send on
  *   its upgrade
  */
case class WebSocketRelay(
    upstream: Request,
    webSocketClient: WebSocketClient = Http.webSocketClient,
    maxPendingBytes: Long = WebSocketRelay.DefaultMaxPendingBytes,
    pingIntervalMillis: Int = 0,
    statsReporter: WebSocketRelayStats => Unit = _ => ()
):
  require(maxPendingBytes >= 0, "maxPendingBytes must be >= 0")
  require(pingIntervalMillis >= 0, "pingIntervalMillis must be >= 0")

  def withUpstream(request: Request): WebSocketRelay = copy(upstream = request)
  def withUpstream(uri: String): WebSocketRelay      = copy(upstream = Request.get(uri))

  def withWebSocketClient(client: WebSocketClient): WebSocketRelay = copy(webSocketClient = client)

  /** Bytes of client frames to queue while the upstream is connecting. */
  def withMaxPendingBytes(bytes: Long): WebSocketRelay = copy(maxPendingBytes = bytes)

  /** Ping the upstream when idle and close it if it stops answering (0 disables). */
  def withPingIntervalMillis(millis: Int): WebSocketRelay = copy(pingIntervalMillis = millis)

  /** Receive each session's [[WebSocketRelayStats]] when the client connection closes. */
  def withStatsReporter(reporter: WebSocketRelayStats => Unit): WebSocketRelay = copy(
    statsReporter = reporter
  )

  /** A handler for one client connection, which opens its own upstream connection. */
  def newHandler(): WebSocketHandler = WebSocketRelaySession(this)

end WebSocketRelay

object WebSocketRelay:
  /** Default for [[WebSocketRelay.maxPendingBytes]]: 1 MiB, about 20 s of base64 PCM16 audio. */
  final val DefaultMaxPendingBytes: Long = 1024 * 1024

  /** The close code sent to the client when the upstream can't be reached. */
  final val UpstreamUnavailable = 4502

  /** The close code a client is sent for an `upstreamCode` the upstream closed with. */
  def clientCloseCode(upstreamCode: Int): Int =
    if upstreamCode == 1000 || upstreamCode == 1001 ||
      (upstreamCode >= 3000 && upstreamCode <= 4999)
    then
      upstreamCode
    else if upstreamCode >= 1002 && upstreamCode <= 1099 then
      4900 + (upstreamCode - 1000)
    else
      4900

  /**
    * The close code the upstream is sent for a `clientCode` the client closed with: a dropped
    * client (1006) is "going away" (1001).
    */
  def upstreamCloseCode(clientCode: Int): Int =
    if clientCode == WebSocketClose.AbnormalClosure then
      1001
    else
      clientCode

  private[http] def connectFailureReason(e: Throwable): String =
    e match
      case h: HttpException if h.statusCode.isDefined =>
        s"upstream status ${h.statusCode.get}"
      case _ =>
        "upstream connect failed"

end WebSocketRelay

private enum PendingFrame:
  case Text(message: String)
  case Binary(data: Array[Byte])

/** One relayed session: the client connection's handler, which owns the upstream connection. */
private class WebSocketRelaySession(config: WebSocketRelay)
    extends WebSocketHandler
    with LogSupport:

  @volatile
  private var client: WebSocketContext = null

  private var startNanos: Long = 0L

  // Guarded by `this`
  private var upstream: WebSocketContext   = null
  private var upstreamOpen                 = false
  private var upstreamDone                 = false
  private var clientDone                   = false
  private val pending                      = mutable.Queue.empty[PendingFrame]
  private var pendingBytes                 = 0L
  private var closedBy: WebSocketRelaySide = null
  private var closeCode                    = 0
  private var closeReason                  = ""

  private val framesToUpstream = AtomicLong(0)
  private val bytesToUpstream  = AtomicLong(0)
  private val framesToClient   = AtomicLong(0)
  private val bytesToClient    = AtomicLong(0)
  private val reported         = AtomicBoolean(false)

  /** Record the first close; returns true when this call was it. */
  private def recordClose(side: WebSocketRelaySide, code: Int, reason: String): Boolean =
    synchronized {
      if closedBy == null then
        closedBy = side
        closeCode = code
        closeReason = reason
        true
      else
        false
    }

  // --- client side ---

  override def onOpen(ctx: WebSocketContext): Unit =
    client = ctx
    startNanos = System.nanoTime()
    val connecting =
      try
        config.webSocketClient.connect(config.upstream, UpstreamHandler, config.pingIntervalMillis)
      catch
        case NonFatal(e) =>
          Rx.exception(e)
    RxRunner.runOnce(connecting) {
      case OnError(e) =>
        upstreamFailed(e)
      case OnNext(_) | OnCompletion =>
        () // UpstreamHandler.onOpen takes over
    }

  override def onTextMessage(ctx: WebSocketContext, message: String): Unit = toUpstream(
    PendingFrame.Text(message)
  )

  override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = toUpstream(
    PendingFrame.Binary(message)
  )

  override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
    recordClose(WebSocketRelaySide.Client, statusCode, reason)
    val up = synchronized {
      clientDone = true
      pending.clear()
      pendingBytes = 0
      if upstreamOpen && !upstreamDone then
        upstream
      else
        null
    }
    // A still-connecting upstream is closed by UpstreamHandler.onOpen.
    if up != null then
      up.close(WebSocketRelay.upstreamCloseCode(statusCode), reason)
    report()

  override def onError(ctx: WebSocketContext, e: Throwable): Unit =
    debug(s"WebSocket relay client error: ${e.getMessage}", e)
    if recordClose(WebSocketRelaySide.Client, WebSocketClose.InternalError, "relay error") then
      ctx.close(WebSocketClose.InternalError, "relay error")

  private def toUpstream(frame: PendingFrame): Unit =
    val size = frameSize(frame)
    // (the open upstream to send to, whether the pre-open queue overflowed)
    val (up, overflow) = synchronized {
      if clientDone || upstreamDone then
        (null, false)
      else if upstreamOpen then
        (upstream, false)
      else if pendingBytes + size > config.maxPendingBytes then
        (null, true)
      else
        pending.enqueue(frame)
        pendingBytes += size
        (null, false)
    }
    if up != null then
      send(up, frame, size)
    else if overflow then
      // Drop the client, which also closes the upstream once it opens.
      recordClose(WebSocketRelaySide.Relay, WebSocketClose.TryAgainLater, "upstream not ready")
      client.close(WebSocketClose.TryAgainLater, "upstream not ready")

  private def send(up: WebSocketContext, frame: PendingFrame, size: Long): Unit =
    try
      frame match
        case PendingFrame.Text(m) =>
          up.send(m)
        case PendingFrame.Binary(d) =>
          up.send(d)
      framesToUpstream.incrementAndGet()
      bytesToUpstream.addAndGet(size)
    catch
      case NonFatal(e) =>
        debug(s"WebSocket relay send to upstream failed: ${e.getMessage}", e)

  private def frameSize(frame: PendingFrame): Long =
    frame match
      case PendingFrame.Text(m) =>
        m.getBytes(StandardCharsets.UTF_8).length
      case PendingFrame.Binary(d) =>
        d.length

  /** The upstream connect or handshake failed (or the upstream closed before opening). */
  private def upstreamFailed(e: Throwable): Unit =
    val reason = WebSocketRelay.connectFailureReason(e)
    debug(s"WebSocket relay upstream connect failed: ${e.getMessage}", e)
    val first = synchronized {
      if upstreamOpen || upstreamDone then
        false
      else
        upstreamDone = true
        pending.clear()
        pendingBytes = 0
        true
    }
    if first then
      recordClose(WebSocketRelaySide.Relay, WebSocketRelay.UpstreamUnavailable, reason)
      client.close(WebSocketRelay.UpstreamUnavailable, reason)

  private def report(): Unit =
    if reported.compareAndSet(false, true) then
      val stats = synchronized {
        WebSocketRelayStats(
          durationMillis = (System.nanoTime() - startNanos) / 1000000L,
          framesToUpstream = framesToUpstream.get(),
          bytesToUpstream = bytesToUpstream.get(),
          framesToClient = framesToClient.get(),
          bytesToClient = bytesToClient.get(),
          statusCode = closeCode,
          reason = closeReason,
          closedBy = closedBy
        )
      }
      try
        config.statsReporter(stats)
      catch
        case NonFatal(e) =>
          warn(s"WebSocket relay stats reporter error: ${e.getMessage}", e)

  // --- upstream side ---

  private object UpstreamHandler extends WebSocketHandler:
    override def onOpen(ctx: WebSocketContext): Unit =
      val closeNow = synchronized {
        upstream = ctx
        clientDone
      }
      if closeNow then
        ctx.close(WebSocketRelay.upstreamCloseCode(closeCode), closeReason)
      else
        flush(ctx)

    /**
      * Send queued frames, then mark the upstream open. Frames the client sends meanwhile join the
      * queue, so they keep their order; no send runs under the lock.
      */
    private def flush(ctx: WebSocketContext): Unit =
      var done       = false
      var clientGone = false
      while !done do
        val batch = synchronized {
          if clientDone then
            clientGone = true
            done = true
            Nil
          else if pending.isEmpty then
            upstreamOpen = true
            done = true
            Nil
          else
            val b = pending.dequeueAll(_ => true)
            pendingBytes = 0
            b
        }
        batch.foreach(f => send(ctx, f, frameSize(f)))
      // The client closed while the queue was flushing, before it could see the upstream open.
      if clientGone then
        ctx.close(WebSocketRelay.upstreamCloseCode(closeCode), closeReason)

    override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
      val c = client
      c.send(message)
      framesToClient.incrementAndGet()
      bytesToClient.addAndGet(message.getBytes(StandardCharsets.UTF_8).length)

    override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit =
      val c = client
      c.send(message)
      framesToClient.incrementAndGet()
      bytesToClient.addAndGet(message.length)

    override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
      val opened = synchronized {
        val o = upstream != null
        if o then
          upstreamDone = true
        o
      }
      if !opened then
        // The JS client reports a failed connect as a close before open.
        upstreamFailed(HttpException.connectionFailed(s"upstream closed with ${statusCode}"))
      else
        recordClose(WebSocketRelaySide.Upstream, statusCode, reason)
        client.close(WebSocketRelay.clientCloseCode(statusCode), reason)

    override def onError(ctx: WebSocketContext, e: Throwable): Unit =
      debug(s"WebSocket relay upstream error: ${e.getMessage}", e)
      recordClose(WebSocketRelaySide.Upstream, WebSocketClose.InternalError, "relay error")
      ctx.close(WebSocketClose.InternalError, "relay error")

  end UpstreamHandler

end WebSocketRelaySession
