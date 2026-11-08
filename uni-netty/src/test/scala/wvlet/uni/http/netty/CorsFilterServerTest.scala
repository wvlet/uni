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

import wvlet.uni.http.{CorsFilter, CorsPolicy, HttpMethod}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

object CorsFilterServerTest:
  class HelloController:
    @Endpoint(HttpMethod.GET, "/hello")
    def hello(): String = "hello"

class CorsFilterServerTest extends UniTest:
  import CorsFilterServerTest.*

  private val App    = "https://app.example.com"
  private val client = HttpClient.newHttpClient()

  private def withServer[A](f: String => A): A =
    val server = NettyServerConfig()
      .withPort(0)
      .noNativeTransport
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withFilter(CorsFilter(CorsPolicy().withAllowedOrigins(App)))
      .withRxHandler(RouterHandler(Router.of[HelloController]))
      .start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def send(builder: HttpRequest.Builder): HttpResponse[String] = client.send(
    builder.header("Origin", App).build(),
    HttpResponse.BodyHandlers.ofString()
  )

  test("answer a preflight for a path with no route") {
    withServer { base =>
      val response = send(
        HttpRequest
          .newBuilder(URI.create(s"${base}/no/such/route"))
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
          .header("Access-Control-Request-Method", "POST")
      )
      response.statusCode() shouldBe 204
      response.headers().firstValue("Access-Control-Allow-Origin").orElse("") shouldBe App
    }
  }

  test("add CORS headers to a routed response") {
    withServer { base =>
      val response = send(HttpRequest.newBuilder(URI.create(s"${base}/hello")).GET())
      response.statusCode() shouldBe 200
      response.body() shouldBe "hello"
      response.headers().firstValue("Access-Control-Allow-Origin").orElse("") shouldBe App
    }
  }

end CorsFilterServerTest
