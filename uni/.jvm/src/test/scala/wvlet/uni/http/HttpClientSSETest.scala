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

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxEvent, RxRunner}
import wvlet.uni.test.UniTest

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Client-side SSE tests against a loopback server. Response bodies are held behind latches so that
  * each test controls when the client can read them.
  */
class HttpClientSSETest extends UniTest:

  private val events = "event: message\ndata: {\"text\":\"こんにちは\"}\n\nid: 2\ndata: done\n\n"

  private class Server(handler: HttpExchange => Unit):
    val requestCount   = AtomicInteger(0)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/events",
      exchange =>
        requestCount.incrementAndGet()
        try handler(exchange)
        finally exchange.close()
    )

    server.start()

    def url: String  = s"http://127.0.0.1:${server.getAddress.getPort}/events"
    def stop(): Unit = server.stop(0)

  private def withServer[A](handler: HttpExchange => Unit)(body: Server => A): A =
    val server = Server(handler)
    try body(server)
    finally server.stop()

  private def respond(
      exchange: HttpExchange,
      status: Int,
      contentType: String,
      chunks: Seq[String],
      beforeChunk: Int => Unit = _ => ()
  ): Unit =
    exchange.getResponseHeaders.set("Content-Type", contentType)
    // Length 0 selects chunked transfer encoding
    exchange.sendResponseHeaders(status, 0)
    val out = exchange.getResponseBody
    chunks
      .zipWithIndex
      .foreach { (chunk, i) =>
        beforeChunk(i)
        out.write(chunk.getBytes(UTF_8))
        out.flush()
      }

  private def await(latch: CountDownLatch): Unit = latch.await(5, TimeUnit.SECONDS) shouldBe true

  /**
    * Records handler callbacks; `terminal` receives OnCompletion or OnError.
    */
  private class RecordingHandler extends ServerSentEventHandler:
    val connected = AtomicReference[Option[HttpResponse]](None)
    val received  = LinkedBlockingQueue[ServerSentEvent]()
    val terminal  = LinkedBlockingQueue[RxEvent]()

    override def onConnect(response: HttpResponse): Unit = connected.set(Some(response))
    override def onEvent(e: ServerSentEvent): Unit       = received.add(e)
    override def onError(e: Throwable): Unit             = terminal.add(OnError(e))
    override def onCompletion(): Unit                    = terminal.add(OnCompletion)

    def nextEvent(): ServerSentEvent =
      val e = received.poll(5, TimeUnit.SECONDS)
      if e == null then
        fail("no SSE event within five seconds")
      e

    def awaitTerminal(): RxEvent =
      val e = terminal.poll(5, TimeUnit.SECONDS)
      if e == null then
        fail("no terminal callback within five seconds")
      e

  end RecordingHandler

  /**
    * Run the stream until it terminates and return the emitted values and the terminal event.
    * `Rx.toSeq` is not used because it can return before an asynchronous stream completes.
    */
  private def runStream[A](
      stream: Rx[A],
      afterSubscribe: () => Unit = () => ()
  ): (Seq[A], RxEvent) =
    val values   = CopyOnWriteArrayList[A]()
    val terminal = LinkedBlockingQueue[RxEvent]()
    val c        =
      RxRunner.run(stream) {
        case OnNext(v) =>
          values.add(v.asInstanceOf[A])
        case other =>
          terminal.add(other)
      }
    try
      afterSubscribe()
      val e = terminal.poll(5, TimeUnit.SECONDS)
      if e == null then
        fail("stream did not terminate within five seconds")
      (values.asScala.toSeq, e)
    finally
      c.cancel

  test("sync send streams events to the request's handler without waiting for the body") {
    val release = CountDownLatch(1)
    withServer { exchange =>
      respond(
        exchange,
        200,
        "text/event-stream",
        Seq("event: message\ndata: first\n\n", "data: second\n\n"),
        beforeChunk =
          i =>
            if i == 1 then
              await(release)
      )
    } { server =>
      val handler = RecordingHandler()
      val client  = Http.client.noRetry.newSyncClient
      try
        val response = client.send(Request.get(server.url).withEventHandler(handler))
        response.status shouldBe HttpStatus.Ok_200
        response.content shouldBe HttpContent.Empty
        handler.connected.get().map(_.status) shouldBe Some(HttpStatus.Ok_200)

        // The second event is held by the server until after send has returned
        handler.nextEvent() shouldBe ServerSentEvent(data = "first", event = Some("message"))
        release.countDown()
        handler.nextEvent().data shouldBe "second"
        handler.awaitTerminal() shouldBe OnCompletion
        server.requestCount.get() shouldBe 1
      finally
        client.close()
    }
  }

  test("sync send without a handler still buffers the event stream") {
    withServer(respond(_, 200, "text/event-stream", Seq(events))) { server =>
      val client = Http.client.noRetry.newSyncClient
      try
        val response = client.send(Request.get(server.url))
        response.contentAsString shouldBe Some(events)
      finally
        client.close()
    }
  }

  test("sync send with a handler reports a non-success status and body without events") {
    withServer(
      respond(_, 401, "application/json", Seq("""{"error":{"message":"invalid API key"}}"""))
    ) { server =>
      val handler = RecordingHandler()
      val client  = Http.client.noRetry.newSyncClient
      try
        val e = intercept[HttpException] {
          client.send(Request.get(server.url).withEventHandler(handler))
        }
        e.statusCode shouldBe Some(401)
        e.contentAsString.getOrElse("") shouldContain "invalid API key"
        handler.received.isEmpty shouldBe true
        server.requestCount.get() shouldBe 1
      finally
        client.close()
    }
  }

  test("sendSSE reports the status to onConnect, then streams events and completes") {
    withServer(respond(_, 200, "text/event-stream", events.grouped(7).toSeq)) { server =>
      val handler = RecordingHandler()
      val client  = Http.client.noRetry.newAsyncClient
      try
        val stream = client.sendSSE(Request.get(server.url).withEventHandler(handler))
        // The request is sent on subscription, so no event can be emitted before it
        server.requestCount.get() shouldBe 0

        val (received, terminal) = runStream(stream)
        terminal shouldBe OnCompletion
        received.map(_.data) shouldBe Seq("""{"text":"こんにちは"}""", "done")
        received.map(_.id) shouldBe Seq(None, Some("2"))
        handler.connected.get().map(_.status) shouldBe Some(HttpStatus.Ok_200)
        handler.received.asScala.toSeq shouldBe received
        handler.awaitTerminal() shouldBe OnCompletion
        server.requestCount.get() shouldBe 1
      finally
        client.close()
    }
  }

  test("sendSSE reports a non-success event stream to onConnect and fails") {
    val ready = CountDownLatch(1)
    withServer { exchange =>
      respond(exchange, 401, "text/event-stream", Seq(events), beforeChunk = _ => await(ready))
    } { server =>
      val handler = RecordingHandler()
      val client  = Http.client.noRetry.newAsyncClient
      try
        val (received, terminal) = runStream(
          client.sendSSE(Request.get(server.url).withEventHandler(handler)),
          () => ready.countDown()
        )
        received.isEmpty shouldBe true
        terminal shouldMatch { case OnError(e: HttpException) =>
          e.statusCode shouldBe Some(401)
        }
        handler.connected.get().map(_.status) shouldBe Some(HttpStatus.Unauthorized_401)
        handler.received.isEmpty shouldBe true
        handler.awaitTerminal() shouldMatch { case OnError(_: HttpException) =>
        }
      finally
        client.close()
    }
  }

  test("sendSSE fails with the upstream status and error body, sending the request once") {
    val body = """{"error":{"message":"invalid API key"}}"""
    for retry <- Seq(true, false) do
      withServer(respond(_, 401, "application/json", Seq(body))) { server =>
        val base   = Http.client.withMaxRetry(3)
        val client =
          if retry then
            base.newAsyncClient
          else
            base.noRetry.newAsyncClient
        try
          val (_, terminal) = runStream(client.sendSSE(Request.get(server.url)))
          terminal shouldMatch { case OnError(e: HttpException) =>
            e.statusCode shouldBe Some(401)
            e.contentAsString shouldBe Some(body)
            e.response.flatMap(_.contentType).map(_.fullType) shouldBe Some("application/json")
          }
          server.requestCount.get() shouldBe 1
        finally
          client.close()
      }
  }

  test("sendSSE does not retry a server error") {
    withServer(respond(_, 503, "text/plain", Seq("overloaded"))) { server =>
      val client = Http.client.withMaxRetry(3).newAsyncClient
      try
        val (_, terminal) = runStream(client.sendSSE(Request.get(server.url)))
        terminal shouldMatch { case OnError(e: HttpException) =>
          e.statusCode shouldBe Some(503)
        }
        server.requestCount.get() shouldBe 1
      finally
        client.close()
    }
  }

end HttpClientSSETest
