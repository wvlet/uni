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

import wvlet.uni.http.{Http, HttpServer, Response}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

class HttpServerProviderTest extends UniTest:

  test("Http.server starts Netty, found through ServiceLoader") {
    val server = Http
      .server
      .withPort(0)
      .withName("portable")
      .withHandler(_ => Response.ok("hello"))
      .start()
    try
      server shouldMatch { case _: NettyHttpServer =>
      }
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}/"))
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      response.statusCode() shouldBe 200
      response.body() shouldBe "hello"
    finally
      server.stop()
  }

  private def postStatus(server: HttpServer, body: String): Int = HttpClient
    .newHttpClient()
    .send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}/"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    .statusCode()

  test("Http.server maps maxContentLength to Netty") {
    Http
      .server
      .withPort(0)
      .withMaxContentLength(16)
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        postStatus(server, "small") shouldBe 200
        postStatus(server, "x" * 100) shouldBe 413
      }
  }

  test("withBackend(NettyServerConfig()...) sets Netty-only options") {
    Http
      .server
      .withPort(0)
      .withBackend(NettyServerConfig().withMaxContentLength(16).withHandlerExecutorThreads(2))
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        postStatus(server, "small") shouldBe 200
        postStatus(server, "x" * 100) shouldBe 413
      }
  }

  test("Http.server's settings take precedence over the backend config's") {
    Http
      .server
      .withPort(0)
      .withMaxContentLength(1024)
      // The port and size limit set here are overridden by the ones above
      .withBackend(NettyServerConfig().withPort(1).withMaxContentLength(16))
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        (server.localPort != 1) shouldBe true
        postStatus(server, "x" * 100) shouldBe 200
      }
  }

end HttpServerProviderTest
