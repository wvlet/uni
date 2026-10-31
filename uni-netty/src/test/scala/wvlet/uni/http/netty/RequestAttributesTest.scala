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

import wvlet.uni.http.{
  HttpMethod,
  Request,
  RequestAttribute,
  RequestAttributes,
  Response,
  RxHttpFilter
}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

object RequestAttributesTest:
  val Identity: RequestAttribute[String] = RequestAttribute("identity")

  // Resolves the identity from a bearer token, as an authentication filter would
  val auth: RxHttpFilter = RxHttpFilter { (request, next) =>
    request.header("Authorization").map(_.stripPrefix("Bearer ")) match
      case Some(user) =>
        next.handle(request.withAttribute(Identity, user))
      case None =>
        next.handle(request)
  }

  class IdentityController:
    @Endpoint(HttpMethod.GET, "/whoami")
    def whoami(request: Request): String = request.attribute(Identity).getOrElse("anonymous")

    @Endpoint(HttpMethod.GET, "/attrs")
    def attrs(attributes: RequestAttributes): String = attributes
      .get(Identity)
      .getOrElse("anonymous")

    @Endpoint(HttpMethod.GET, "/remote")
    def remote(request: Request): String = request
      .attribute(RequestAttribute.RemoteAddress)
      .getOrElse("none")

end RequestAttributesTest

class RequestAttributesTest extends UniTest:
  import RequestAttributesTest.*

  private val client = HttpClient.newHttpClient()

  private def withServer[A](f: String => A): A =
    val server = NettyServerConfig()
      .withPort(0)
      .noNativeTransport
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withFilter(auth)
      .withRxHandler(RouterHandler(Router.of[IdentityController]))
      .start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def get(url: String, headers: (String, String)*): String =
    val builder = HttpRequest.newBuilder(URI.create(url)).GET()
    headers.foreach((k, v) => builder.header(k, v))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8)).body()

  test("pass an identity from a filter to the controller") {
    withServer { base =>
      get(s"${base}/whoami", "Authorization" -> "Bearer alice") shouldBe "alice"
      get(s"${base}/attrs", "Authorization" -> "Bearer alice") shouldBe "alice"
      get(s"${base}/whoami") shouldBe "anonymous"
    }
  }

  test("ignore headers named like an attribute") {
    withServer { base =>
      get(s"${base}/whoami", "identity" -> "mallory", "X-Identity" -> "mallory") shouldBe
        "anonymous"
    }
  }

  test("keep attributes isolated between concurrent requests") {
    withServer { base =>
      val pool = Executors.newFixedThreadPool(8)
      try
        val users   = (1 to 40).map(i => s"user${i}")
        val results = pool
          .invokeAll(
            users
              .map { u =>
                (() => get(s"${base}/whoami", "Authorization" -> s"Bearer ${u}")): Callable[String]
              }
              .asJava
          )
          .asScala
          .map(_.get())
        results.toSeq shouldBe users
      finally
        pool.shutdown()
    }
  }

  test("set the remote address of the connection") {
    withServer { base =>
      get(s"${base}/remote") shouldMatch {
        case s: String if s.startsWith("127.0.0.1:") =>
      }
    }
  }

end RequestAttributesTest
