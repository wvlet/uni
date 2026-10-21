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

import wvlet.uni.http.{HttpMethod, Request, Response, RxHttpFilter, RxHttpHandler}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.rx.Rx
import wvlet.uni.surface.required
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

object RouterHandlerErrorTest:
  class ErrorController:
    @Endpoint(HttpMethod.GET, "/error")
    def error(): String = throw IllegalArgumentException("classified failure")

    @Endpoint(HttpMethod.GET, "/rx-error")
    def rxError(): Rx[String] = Rx.exception(IllegalArgumentException("async failure"))

    @Endpoint(HttpMethod.GET, "/state-error")
    def stateError(): String = throw IllegalStateException("password=hunter2")

    @Endpoint(HttpMethod.GET, "/count")
    def count(
        @required
        n: Int
    ): String = s"${n}"

  // Maps IllegalArgumentException to 400, as an application's centralized error filter would
  val classifier: RxHttpFilter = RxHttpFilter { (request, next) =>
    next
      .handle(request)
      .recover { case e: IllegalArgumentException =>
        Response.badRequest(e.getMessage)
      }
  }

  class ClassifierFilter extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] = classifier(
      request,
      next
    )

  class FilteredController:
    @Endpoint(HttpMethod.GET, "/filtered-error")
    def error(): String = throw IllegalArgumentException("router filter")

end RouterHandlerErrorTest

class RouterHandlerErrorTest extends UniTest:
  import RouterHandlerErrorTest.*

  private val client = HttpClient.newHttpClient()

  private def serverConfig: NettyServerConfig = NettyServer
    .withPort(0)
    .noNativeTransport
    .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
    .withRxHandler(RouterHandler(Router.of[ErrorController]))

  private def withServer[A](config: NettyServerConfig)(f: String => A): A =
    val server = config.start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def get(url: String): HttpResponse[String] = client.send(
    HttpRequest.newBuilder(URI.create(url)).GET().build(),
    HttpResponse.BodyHandlers.ofString(UTF_8)
  )

  test("let an outer filter classify the original controller exception") {
    withServer(serverConfig.withFilter(classifier)) { base =>
      val sync = get(s"${base}/error")
      sync.statusCode() shouldBe 400
      sync.body() shouldBe "classified failure"

      val async = get(s"${base}/rx-error")
      async.statusCode() shouldBe 400
      async.body() shouldBe "async failure"

      // An exception the filter does not handle still becomes a generic 500
      val unhandled = get(s"${base}/state-error")
      unhandled.statusCode() shouldBe 500
      unhandled.body() shouldBe "Internal Server Error"
    }
  }

  test("let a router-level @Filter classify the controller exception") {
    withServer(
      serverConfig.withRxHandler(
        RouterHandler(Router.filter[ClassifierFilter].andThen(Router.of[FilteredController]))
      )
    ) { base =>
      val resp = get(s"${base}/filtered-error")
      resp.statusCode() shouldBe 400
      resp.body() shouldBe "router filter"
    }
  }

  test("render an unrecovered controller exception as a generic 500") {
    withServer(serverConfig) { base =>
      val resp = get(s"${base}/state-error")
      resp.statusCode() shouldBe 500
      resp.body() shouldBe "Internal Server Error"
    }
  }

  test("show the exception message with the server's detailed errors") {
    withServer(serverConfig.withDetailedErrors(true)) { base =>
      val resp = get(s"${base}/state-error")
      resp.statusCode() shouldBe 500
      resp.body() shouldBe "password=hunter2"
    }
  }

  test("keep parameter mapping errors as 400") {
    withServer(serverConfig) { base =>
      get(s"${base}/count").statusCode() shouldBe 400
    }
  }

  test("return the controller exception as a failed Rx") {
    val handler = RouterHandler(Router.of[ErrorController])
    val result  = handler
      .handle(Request.get("/error"))
      .recover { case e: IllegalArgumentException =>
        Response.badRequest(e.getMessage)
      }
    result.toSeq.head.contentAsString shouldBe Some("classified failure")
  }

end RouterHandlerErrorTest
