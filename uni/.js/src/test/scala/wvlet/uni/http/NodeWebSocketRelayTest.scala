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

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Readable rejections and [[WebSocketRelay]] on the Node server, with the JS client (the global
  * `WebSocket`, Node.js >= 22). The async counterpart of the JVM/Native `WebSocketRelaySpec`.
  */
class NodeWebSocketRelayTest extends UniTest:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Values in arrival order, each awaited by the next `next()`. */
  private class AsyncQueue[A]:
    private val values  = mutable.Queue.empty[A]
    private val waiters = mutable.Queue.empty[Promise[A]]
    def put(a: A): Unit =
      if waiters.nonEmpty then
        waiters.dequeue().success(a)
      else
        values.enqueue(a)

    def next(): Future[A] =
      if values.nonEmpty then
        Future.successful(values.dequeue())
      else
        val p = Promise[A]()
        waiters.enqueue(p)
        p.future

  private class Recorder extends WebSocketHandler:
    val status   = AsyncQueue[(Int, String)]()
    val messages = AsyncQueue[String]()
    override def onTextMessage(ctx: WebSocketContext, message: String): Unit = messages.put(message)
    override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit = status.put(
      (statusCode, reason)
    )

  private def tryConnect(
      request: Request,
      handler: WebSocketHandler
  ): Future[Either[Throwable, WebSocketContext]] =
    val opened = Promise[Either[Throwable, WebSocketContext]]()
    RxRunner.runOnce(Http.webSocketClient.connect(request, handler)) {
      case OnNext(ctx) =>
        opened.trySuccess(Right(ctx.asInstanceOf[WebSocketContext]))
      case OnError(e) =>
        opened.trySuccess(Left(e))
      case _ =>
    }
    opened.future

  private def connect(request: Request, handler: WebSocketHandler): Future[WebSocketContext] =
    tryConnect(request, handler).map {
      case Right(ctx) =>
        ctx
      case Left(e) =>
        throw e
    }

  private def rejectWith(response: => Rx[Response]): RxHttpFilter =
    new RxHttpFilter:
      override def apply(request: Request, next: RxHttpHandler): Rx[Response] = response

  private def withServer(routes: WebSocketRoute*)(body: HttpServer => Future[Unit]): Rx[Unit] =
    routes
      .foldLeft(NodeServerConfig().withPort(0))(_.withWebSocketRoute(_))
      .startAndAwait(server => Rx.future(body(server)))

  test("answer filter rejections with readable close codes") {
    withServer(
      WebSocketRoute(
        "/ws",
        _ => new WebSocketHandler {},
        rejectWith(Rx.single(Response.unauthorized.withTextContent("login required")))
      ).withCloseOnRejection(),
      WebSocketRoute("/empty", _ => new WebSocketHandler {}, rejectWith(Rx.empty))
        .withCloseOnRejection()
    ) { server =>
      val denied = Recorder()
      val empty  = Recorder()
      for
        _ <- connect(Request.get(s"ws://127.0.0.1:${server.localPort}/ws"), denied)
        s <- denied.status.next()
        _ = s shouldBe (4401, "login required")
        _  <- connect(Request.get(s"ws://127.0.0.1:${server.localPort}/empty"), empty)
        s2 <- empty.status.next()
      yield s2._1 shouldBe 4403
    }
  }

  test("keep plain HTTP rejections by default, and origin rejections as HTTP 403") {
    withServer(
      WebSocketRoute(
        "/plain",
        _ => new WebSocketHandler {},
        rejectWith(Rx.single(Response.unauthorized))
      ),
      WebSocketRoute("/readable", _ => new WebSocketHandler {}).withCloseOnRejection()
    ) { server =>
      for
        plain <- tryConnect(Request.get(s"ws://127.0.0.1:${server.localPort}/plain"), Recorder())
        _ = plain.isLeft shouldBe true
        crossSite <- tryConnect(
          Request
            .get(s"ws://127.0.0.1:${server.localPort}/readable")
            .addHeader("Origin", "https://evil.example"),
          Recorder()
        )
      yield crossSite.isLeft shouldBe true
    }
  }

  /** A fake upstream on `/upstream` (and a slow one), with relays to them. */
  private class Upstreams:
    val stats          = AsyncQueue[WebSocketRelayStats]()
    val upstreamStatus = AsyncQueue[(Int, String)]()
    var port           = 0

    def handler(request: Request): WebSocketHandler =
      new WebSocketHandler:
        override def onOpen(ctx: WebSocketContext): Unit = ctx.send(
          s"auth:${request.header(HttpHeader.Authorization).getOrElse("none")}"
        )

        override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
          if message.startsWith("close:") then
            ctx.close(message.stripPrefix("close:").toInt, "bye")
          else
            ctx.send(s"echo:${message}")

        override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
          upstreamStatus.put((statusCode, reason))

    private val slowGate =
      new RxHttpFilter:
        override def apply(request: Request, next: RxHttpHandler): Rx[Response] = Rx
          .delay(500, TimeUnit.MILLISECONDS)
          .flatMap(_ => next.handle(request))

    def relay(
        path: String,
        configure: WebSocketRelay => WebSocketRelay = identity
    ): WebSocketRoute = WebSocketRoute(
      s"/relay${path}",
      _ =>
        configure(
          WebSocketRelay(
            Request.get(s"ws://127.0.0.1:${port}${path}").withBearerToken("upstream-key")
          ).withStatsReporter(stats.put(_))
        ).newHandler()
    )

    def routes: Seq[WebSocketRoute] = Seq(
      WebSocketRoute("/upstream", handler),
      WebSocketRoute("/slow", handler, slowGate),
      WebSocketRoute("/denied", handler, rejectWith(Rx.single(Response.unauthorized))),
      relay("/upstream"),
      relay("/slow"),
      relay("/denied"),
      WebSocketRoute("/relay/overflow", relay("/slow", _.withMaxPendingBytes(4)).handlerFactory),
      WebSocketRoute(
        "/relay/unreachable",
        relay("/upstream", _.withUpstream("ws://127.0.0.1:1/x")).handlerFactory
      )
    )

  end Upstreams

  private def withRelay(body: (Upstreams, String => Request) => Future[Unit]): Rx[Unit] =
    val u = Upstreams()
    withServer(u.routes*) { server =>
      u.port = server.localPort
      body(u, path => Request.get(s"ws://127.0.0.1:${server.localPort}${path}"))
    }

  test("relay frames both ways, mirror closes, and report stats") {
    withRelay { (u, at) =>
      val client = Recorder()
      for
        ctx  <- connect(at("/relay/upstream"), client)
        auth <- client.messages.next()
        _ = auth shouldBe "auth:Bearer upstream-key"
        _ = ctx.send("hello")
        echo <- client.messages.next()
        _ = echo shouldBe "echo:hello"
        _ = ctx.close(4001, "done")
        up <- u.upstreamStatus.next()
        _ = up shouldBe (4001, "done")
        stats <- u.stats.next()
      yield
        stats.closedBy shouldBe WebSocketRelaySide.Client
        stats.statusCode shouldBe 4001
        stats.framesToUpstream shouldBe 1
        stats.bytesToUpstream shouldBe 5
        stats.framesToClient shouldBe 2
    }
  }

  test("map reserved upstream close codes into 4900-4999") {
    withRelay { (u, at) =>
      val client = Recorder()
      for
        ctx <- connect(at("/relay/upstream"), client)
        _   <- client.messages.next()
        _ = ctx.send("close:1011")
        s <- client.status.next()
        _ = s shouldBe (4911, "bye")
        stats <- u.stats.next()
      yield
        stats.closedBy shouldBe WebSocketRelaySide.Upstream
        stats.statusCode shouldBe 1011
    }
  }

  test("queue client frames until the upstream opens, up to the limit") {
    withRelay { (u, at) =>
      val client   = Recorder()
      val overflow = Recorder()
      for
        ctx <- connect(at("/relay/slow"), client)
        _ = ctx.send("a")
        _ = ctx.send("b")
        auth <- client.messages.next()
        _ = auth shouldBe "auth:Bearer upstream-key"
        a <- client.messages.next()
        b <- client.messages.next()
        _ = (a, b) shouldBe ("echo:a", "echo:b")
        _ = ctx.close()
        _    <- u.stats.next()
        ctx2 <- connect(at("/relay/overflow"), overflow)
        _ = ctx2.send("more than four bytes")
        s <- overflow.status.next()
      yield s shouldBe (1013, "upstream not ready")
    }
  }

  test("close the client with 4502 when the upstream can't be reached") {
    withRelay { (u, at) =>
      val denied      = Recorder()
      val unreachable = Recorder()
      for
        _ <- connect(at("/relay/denied"), denied)
        s <- denied.status.next()
        _ = s._1 shouldBe 4502
        _  <- u.stats.next()
        _  <- connect(at("/relay/unreachable"), unreachable)
        s2 <- unreachable.status.next()
      yield s2 shouldBe (4502, "upstream connect failed")
    }
  }

end NodeWebSocketRelayTest
