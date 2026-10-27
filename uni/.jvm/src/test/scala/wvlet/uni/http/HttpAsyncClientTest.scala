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

import com.sun.net.httpserver.HttpServer
import wvlet.uni.test.UniTest

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

class HttpAsyncClientTest extends UniTest:

  private def withServer[A](body: String => A): A =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange =>
        try
          val (status, text) =
            exchange.getRequestURI.getPath match
              case "/hello" =>
                (200, "hello")
              case _ =>
                (404, "not found")
          val bytes = text.getBytes(UTF_8)
          exchange.sendResponseHeaders(status, bytes.length)
          exchange.getResponseBody.write(bytes)
        finally
          exchange.close()
    )
    server.start()
    try body(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  test("send returns the response") {
    withServer { url =>
      val client = Http.client.withBaseUri(url).newAsyncClient
      val resp   = client.send(Request.get("/hello")).await
      resp.contentAsString shouldBe Some("hello")
    }
  }

  test("send fails with HttpException for a 4xx response") {
    withServer { url =>
      val client = Http.client.withBaseUri(url).noRetry.newAsyncClient
      val e      = intercept[HttpException] {
        client.send(Request.get("/missing")).await
      }
      e.statusCode shouldBe Some(404)
    }
  }

end HttpAsyncClientTest
