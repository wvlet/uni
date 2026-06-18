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

import wvlet.uni.test.UniTest

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
