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

import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest

import scala.concurrent.{Future, Promise}

/**
  * Verifies the Node.js HTTP server backend against the cross-platform async (Fetch) client. Each
  * test starts a server on an ephemeral port, waits for the asynchronous bind via `startAndAwait`,
  * then exercises it with `Http.client.newAsyncClient` (async only — the sync client would block
  * the single Node event loop and deadlock against an in-process server).
  */
class NodeServerTest extends UniTest:

  private given scala.concurrent.ExecutionContext =
    scala.scalajs.concurrent.JSExecutionContext.queue

  private def asyncClient(server: HttpServer): HttpAsyncClient =
    Http.client.withBaseUri(s"http://localhost:${server.localPort}").newAsyncClient

  test("should handle a GET request") {
    NodeServer
      .withHandler { request =>
        Response.ok(s"Hello from ${request.path}")
      }
      .withPort(0)
      .startAndAwait { server =>
        asyncClient(server)
          .send(Request.get("/test"))
          .map { response =>
            response.statusCode shouldBe 200
            response.contentAsString shouldBe Some("Hello from /test")
          }
      }
  }

  test("should handle a POST request with a body") {
    NodeServer
      .withHandler { request =>
        Response.ok(s"Received: ${request.content.toContentString}")
      }
      .withPort(0)
      .startAndAwait { server =>
        asyncClient(server)
          .send(Request.post("/data").withContent(HttpContent.text("payload")))
          .map { response =>
            response.statusCode shouldBe 200
            response.contentAsString shouldBe Some("Received: payload")
          }
      }
  }

  /**
    * The first value of an Rx as a Future. Unlike `startAndAwait` (which runs via `runOnce`), this
    * ignores the interim OnCompletion that `Rx.future` emits while the future is still pending, so
    * a failed assertion actually fails the test.
    */
  private def firstValue[A](rx: Rx[A]): Future[A] =
    val p = Promise[A]()
    RxRunner.run(rx) {
      case OnNext(v) =>
        p.trySuccess(v.asInstanceOf[A])
      case OnError(e) =>
        p.tryFailure(e)
      case OnCompletion =>
        ()
    }
    p.future

  /** Start a server, run the async block once it is listening, then stop the server. */
  private def withNodeServer[A](config: NodeServerConfig)(
      block: HttpServer => Future[A]
  ): Future[A] =
    val server = config.withPort(0).start()
    firstValue(server.whenReady)
      .flatMap(block)
      .andThen { case _ =>
        server.stop()
      }

  /** Send a request and return (status, body), including non-2xx responses. */
  private def statusAndBody(server: HttpServer, request: Request): Future[(Int, Option[String])] =
    firstValue(
      asyncClient(server)
        .noRetry
        .send(request)
        .map(r => (r.statusCode, r.contentAsString))
        .recover {
          case e: HttpMaxRetryException =>
            (e.statusCode, e.response.contentAsString)
          case e: HttpException =>
            (e.statusCode.getOrElse(-1), e.contentAsString)
        }
    )

  test("should reject a request body over maxContentLength with 413") {
    val config = NodeServer
      .withHandler { request =>
        Response.ok(s"Received: ${request.content.toContentString}")
      }
      .withMaxContentLength(16)
    withNodeServer(config) { server =>
      for
        (okStatus, okBody) <- statusAndBody(
          server,
          Request.post("/data").withContent(HttpContent.text("small"))
        )
        (status, _) <- statusAndBody(
          server,
          Request.post("/data").withContent(HttpContent.text("x" * 1024))
        )
      yield
        okStatus shouldBe 200
        okBody shouldBe Some("Received: small")
        status shouldBe 413
    }
  }

  test("should hide exception messages in 500 responses") {
    val config = NodeServer.withHandler { _ =>
      throw IllegalStateException("password=hunter2")
    }
    withNodeServer(config) { server =>
      statusAndBody(server, Request.get("/")).map { (status, body) =>
        status shouldBe 500
        body shouldBe Some("Internal Server Error")
      }
    }
  }

  test("should round-trip request and response headers") {
    NodeServer
      .withHandler { request =>
        val echoed = request.header("X-Echo").getOrElse("none")
        Response.ok("ok").addHeader("X-Echoed", echoed)
      }
      .withPort(0)
      .startAndAwait { server =>
        asyncClient(server)
          .send(Request.get("/headers").addHeader("X-Echo", "ping"))
          .map { response =>
            response.statusCode shouldBe 200
            response.header("X-Echoed") shouldBe Some("ping")
          }
      }
  }

  test("should return 404 from the default handler") {
    NodeServer
      .withPort(0)
      .startAndAwait { server =>
        asyncClient(server)
          .send(Request.get("/missing"))
          .map { response =>
            response.statusCode shouldBe 404
          }
      }
  }

  test("should report a bound ephemeral port once ready") {
    NodeServer
      .withPort(0)
      .startAndAwait { server =>
        import wvlet.uni.rx.Rx
        Rx.single {
          (server.localPort > 0) shouldBe true
          server.isRunning shouldBe true
        }
      }
  }

  test("should reject the synchronous start(block) on Node") {
    // Node binds asynchronously, so the sync block form would run before the server is listening.
    intercept[UnsupportedOperationException] {
      NodeServer
        .withPort(0)
        .start { _ =>
          ()
        }
    }
  }

  test("should stream Server-Sent Events") {
    import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}
    import scala.concurrent.Promise

    val events = Seq(ServerSentEvent.data("hello"), ServerSentEvent.data("world"))
    NodeServer
      .withRxHandler { _ =>
        Rx.single(Response.eventStream(Rx.fromSeq(events)))
      }
      .withPort(0)
      .startAndAwait { server =>
        // Collect the streamed events into a single terminal value (the test framework completes
        // on the first emission, so aggregate via the stream's OnCompletion).
        val received = Promise[Seq[String]]()
        val buffer   = scala.collection.mutable.ListBuffer.empty[String]
        RxRunner.run(asyncClient(server).sendSSE(Request.get("/events"))) {
          case OnNext(event) =>
            buffer += event.asInstanceOf[ServerSentEvent].data
          case OnError(e) =>
            received.failure(e)
          case OnCompletion =>
            received.success(buffer.toSeq)
        }
        Rx.future(received.future)
          .map { data =>
            data shouldBe Seq("hello", "world")
          }
      }
  }

end NodeServerTest
