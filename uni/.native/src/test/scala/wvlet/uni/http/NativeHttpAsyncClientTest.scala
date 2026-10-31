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

import wvlet.uni.control.Retry
import wvlet.uni.test.UniTest

import java.util.concurrent.atomic.AtomicInteger

/**
  * Runs the async client (`CurlAsyncChannel`) against the in-process Native server over loopback: a
  * 2xx body, a 4xx failure, and a 5xx that exhausts the `Rx.delay`-based retry loop.
  */
class NativeHttpAsyncClientTest extends UniTest:

  private def withServer[A](body: (String, AtomicInteger) => A): A =
    val hits = AtomicInteger(0)
    NativeServerConfig()
      .withHandler { req =>
        hits.incrementAndGet()
        req.path match
          case "/hello" =>
            Response.ok("hello")
          case "/unavailable" =>
            Response(HttpStatus.ServiceUnavailable_503).withTextContent("busy")
          case _ =>
            Response.notFound("not found")
      }
      .withPort(0)
      .start { server =>
        body(s"http://127.0.0.1:${server.localPort}", hits)
      }

  test("send returns the response") {
    withServer { (url, _) =>
      val client = Http.client.withBaseUri(url).newAsyncClient
      val resp   = client.send(Request.get("/hello")).await
      resp.contentAsString shouldBe Some("hello")
    }
  }

  test("send fails with HttpException for a 4xx response") {
    withServer { (url, _) =>
      val client = Http.client.withBaseUri(url).newAsyncClient
      val e      = intercept[HttpException] {
        client.send(Request.get("/missing")).await
      }
      e.statusCode shouldBe Some(404)
    }
  }

  test("send fails with HttpMaxRetryException after retrying a 5xx response") {
    withServer { (url, hits) =>
      val client =
        Http
          .client
          .withBaseUri(url)
          .withRetryContext(Retry.withBackOff(maxRetry = 2, initialIntervalMillis = 10))
          .newAsyncClient
      val e = intercept[HttpMaxRetryException] {
        client.send(Request.get("/unavailable")).await
      }
      e.statusCode shouldBe 503
      // maxRetry bounds the total number of attempts
      e.retryContext.retryCount shouldBe 2
      hits.get() shouldBe 2
    }
  }

end NativeHttpAsyncClientTest
