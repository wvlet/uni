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

import wvlet.uni.rx.{OnError, OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest

import java.net.ServerSocket
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

/**
  * Readable rejections and [[WebSocketRelay]] on one server backend, driven by the platform's
  * WebSocket client. A subclass starts the backend's server with the given routes.
  */
abstract class WebSocketRelaySpec extends UniTest:

  /** Start this backend's server on an ephemeral port with `routes`. */
  protected def startServer(routes: Seq[WebSocketRoute]): HttpServer

  private val Timeout = 10L

  /** The outcome of a connect: the open context, or the handshake error. */
  private def tryConnect(
      request: Request,
      handler: WebSocketHandler
  ): Either[Throwable, WebSocketContext] =
    val ref   = AtomicReference[Either[Throwable, WebSocketContext]]()
    val ready = CountDownLatch(1)
    RxRunner.runOnce(Http.webSocketClient.connect(request, handler)) {
      case OnNext(ctx) =>
        ref.set(Right(ctx.asInstanceOf[WebSocketContext]))
        ready.countDown()
      case OnError(e) =>
        ref.set(Left(e))
        ready.countDown()
      case _ =>
    }
    ready.await(Timeout, TimeUnit.SECONDS) shouldBe true
    ref.get()

  private def connect(request: Request, handler: WebSocketHandler): WebSocketContext =
    tryConnect(request, handler) match
      case Right(ctx) =>
        ctx
      case Left(e) =>
        throw e

  private class Recorder extends WebSocketHandler:
    val status   = LinkedBlockingQueue[(Int, String)]()
    val messages = LinkedBlockingQueue[String]()
    val binary   = LinkedBlockingQueue[Array[Byte]]()
    override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(message)
    override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = binary.put(
      message
    )

    override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit = status.put(
      (statusCode, reason)
    )

    def nextMessage(): String       = messages.poll(Timeout, TimeUnit.SECONDS)
    def nextStatus(): (Int, String) = status.poll(Timeout, TimeUnit.SECONDS)

  private def rejectWith(response: => Rx[Response]): RxHttpFilter =
    new RxHttpFilter:
      override def apply(request: Request, next: RxHttpHandler): Rx[Response] = response

  private def statusOf(e: Throwable): Option[Int] =
    e match
      case h: HttpException =>
        h.statusCode
      case _ =>
        None

  // --- readable rejections ---

  test("answer a filter rejection with close 4000 + status and the body as the reason") {
    val opened = AtomicInteger(0)
    val server = startServer(
      Seq(
        WebSocketRoute(
          "/ws",
          _ =>
            opened.incrementAndGet()
            new WebSocketHandler {}
          ,
          rejectWith(Rx.single(Response.unauthorized.withTextContent("login required")))
        ).withCloseOnRejection()
      )
    )
    try
      val client = Recorder()
      connect(Request.get(s"ws://localhost:${server.localPort}/ws"), client)
      client.nextStatus() shouldBe (4401, "login required")
      // The route's handler is never created for a rejected upgrade.
      opened.get() shouldBe 0
    finally
      server.stop()
  }

  test("answer an empty filter result as 4403 and a filter error as 4500") {
    val server = startServer(
      Seq(
        WebSocketRoute("/empty", _ => new WebSocketHandler {}, rejectWith(Rx.empty))
          .withCloseOnRejection(),
        WebSocketRoute(
          "/error",
          _ => new WebSocketHandler {},
          rejectWith(Rx.exception(IllegalStateException("boom")))
        ).withCloseOnRejection()
      )
    )
    // The server logs the filter error, possibly after the close frame reaches the client, so the
    // capture also covers the server's shutdown
    captureLogs {
      try
        val empty = Recorder()
        connect(Request.get(s"ws://localhost:${server.localPort}/empty"), empty)
        empty.nextStatus()._1 shouldBe 4403
        val failed = Recorder()
        connect(Request.get(s"ws://localhost:${server.localPort}/error"), failed)
        failed.nextStatus()._1 shouldBe 4500
      finally
        server.stop()
    }
  }

  test("keep plain HTTP rejections by default, and origin rejections as HTTP 403") {
    val server = startServer(
      Seq(
        WebSocketRoute(
          "/plain",
          _ => new WebSocketHandler {},
          rejectWith(Rx.single(Response.unauthorized))
        ),
        WebSocketRoute("/readable", _ => new WebSocketHandler {}).withCloseOnRejection()
      )
    )
    try
      val plain = tryConnect(Request.get(s"ws://localhost:${server.localPort}/plain"), Recorder())
      plain shouldMatch {
        case Left(e) if statusOf(e) == Some(401) =>
      }
      // The origin check runs before the filter, so a cross-site page never gets a handshake.
      val crossSite = tryConnect(
        Request
          .get(s"ws://localhost:${server.localPort}/readable")
          .addHeader("Origin", "https://evil.example"),
        Recorder()
      )
      crossSite shouldMatch {
        case Left(e) if statusOf(e) == Some(403) =>
      }
    finally
      server.stop()
  }

  // --- relay ---

  /**
    * A fake upstream and a relay to it on one server, with the relay's stats and upstream closes.
    */
  private class RelayFixture(relay: (Int, WebSocketRelay) => WebSocketRelay = (_, r) => r):
    val stats          = LinkedBlockingQueue[WebSocketRelayStats]()
    val upstreamStatus = LinkedBlockingQueue[(Int, String)]()
    private val port   = AtomicInteger(0)

    private def upstreamHandler(request: Request): WebSocketHandler =
      new WebSocketHandler:
        override def onOpen(ctx: WebSocketContext): Unit = ctx.send(
          s"auth:${request.header(HttpHeader.Authorization).getOrElse("none")}"
        )

        override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
          if message.startsWith("close:") then
            ctx.close(message.stripPrefix("close:").toInt, "bye")
          else
            ctx.send(s"echo:${message}")

        override def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = ctx.send(
          message
        )

        override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
          upstreamStatus.put((statusCode, reason))

    private def relayTo(path: String): Request => WebSocketHandler =
      _ =>
        relay(
          port.get(),
          WebSocketRelay(
            Request.get(s"ws://localhost:${port.get()}${path}").withBearerToken("upstream-key")
          ).withStatsReporter(stats.put(_))
        ).newHandler()

    // The slow upstream holds its handshake, so the relay queues the client's first frames.
    private val slowGate =
      new RxHttpFilter:
        override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
          Thread.sleep(500)
          next.handle(request)

    val server: HttpServer = startServer(
      Seq(
        WebSocketRoute("/upstream", upstreamHandler),
        WebSocketRoute("/slow-upstream", upstreamHandler, slowGate),
        WebSocketRoute(
          "/denied-upstream",
          upstreamHandler,
          rejectWith(Rx.single(Response.unauthorized))
        ),
        WebSocketRoute("/relay", relayTo("/upstream")),
        WebSocketRoute("/relay-slow", relayTo("/slow-upstream")),
        WebSocketRoute("/relay-denied", relayTo("/denied-upstream"))
      )
    )

    port.set(server.localPort)

    def open(path: String, client: Recorder): WebSocketContext = connect(
      Request.get(s"ws://localhost:${server.localPort}${path}"),
      client
    )

    def nextStats(): WebSocketRelayStats = stats.poll(Timeout, TimeUnit.SECONDS)

    def close(): Unit = server.stop()

  end RelayFixture

  test("relay text and binary frames both ways, with the upstream's headers") {
    val f = RelayFixture()
    try
      val client = Recorder()
      val ctx    = f.open("/relay", client)
      client.nextMessage() shouldBe "auth:Bearer upstream-key"
      ctx.send("hello")
      client.nextMessage() shouldBe "echo:hello"
      ctx.send(Array[Byte](1, 2, 3))
      client.binary.poll(Timeout, TimeUnit.SECONDS).toSeq shouldBe Seq[Byte](1, 2, 3)
      ctx.close(4001, "done")
      // The client's close reaches the upstream as is.
      f.upstreamStatus.poll(Timeout, TimeUnit.SECONDS) shouldBe (4001, "done")
      val stats = f.nextStats()
      stats.closedBy shouldBe WebSocketRelaySide.Client
      stats.statusCode shouldBe 4001
      stats.reason shouldBe "done"
      stats.framesToUpstream shouldBe 2
      stats.bytesToUpstream shouldBe 8
      // auth:..., echo:hello, and the binary echo
      stats.framesToClient shouldBe 3
      stats.bytesToClient shouldBe ("auth:Bearer upstream-key".length + "echo:hello".length + 3)
      (stats.durationMillis >= 0) shouldBe true
    finally
      f.close()
  }

  test("map upstream close codes the client can't be sent into 4900-4999") {
    val f = RelayFixture()
    try
      val reserved = Recorder()
      val ctx      = f.open("/relay", reserved)
      reserved.nextMessage()
      ctx.send("close:1011")
      reserved.nextStatus() shouldBe (4911, "bye")
      val stats = f.nextStats()
      stats.closedBy shouldBe WebSocketRelaySide.Upstream
      stats.statusCode shouldBe 1011

      val app  = Recorder()
      val ctx2 = f.open("/relay", app)
      app.nextMessage()
      ctx2.send("close:4005")
      app.nextStatus() shouldBe (4005, "bye")
    finally
      f.close()
  }

  test("queue client frames until the upstream opens") {
    val f = RelayFixture()
    try
      val client = Recorder()
      val ctx    = f.open("/relay-slow", client)
      ctx.send("a")
      ctx.send("b")
      client.nextMessage() shouldBe "auth:Bearer upstream-key"
      client.nextMessage() shouldBe "echo:a"
      client.nextMessage() shouldBe "echo:b"
      ctx.close()
      f.nextStats().framesToUpstream shouldBe 2
    finally
      f.close()
  }

  test("close the client with 1013 when the pre-open queue overflows") {
    val f = RelayFixture((_, r) => r.withMaxPendingBytes(4))
    try
      val client = Recorder()
      val ctx    = f.open("/relay-slow", client)
      ctx.send("more than four bytes")
      client.nextStatus() shouldBe (1013, "upstream not ready")
      val stats = f.nextStats()
      stats.closedBy shouldBe WebSocketRelaySide.Relay
      stats.framesToUpstream shouldBe 0
    finally
      f.close()
  }

  test("close the client with 4502 when the upstream can't be reached") {
    val closedPort =
      val s = ServerSocket(0)
      try s.getLocalPort
      finally s.close()
    val f = RelayFixture()
    try
      val denied = Recorder()
      f.open("/relay-denied", denied)
      denied.nextStatus() shouldBe (4502, "upstream status 401")
      val stats = f.nextStats()
      stats.closedBy shouldBe WebSocketRelaySide.Relay
      stats.statusCode shouldBe 4502
    finally
      f.close()

    val unreachable = RelayFixture((_, r) => r.withUpstream(s"ws://localhost:${closedPort}/x"))
    try
      val client = Recorder()
      unreachable.open("/relay", client)
      client.nextStatus() shouldBe (4502, "upstream connect failed")
    finally
      unreachable.close()
  }

end WebSocketRelaySpec
