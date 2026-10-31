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
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

/**
  * Runs the async client (`FetchChannel`) against an in-process Node server: a 2xx body, a 4xx
  * failure, and a 5xx that exhausts the `Rx.delay`-based retry loop. `Rx.await` is unavailable on
  * Scala.js, so each test returns its `Rx` and failures are recovered into values to assert on.
  */
class NodeHttpAsyncClientTest extends UniTest:

  private def withServer[A](body: (String, () => Int) => Rx[A]): Rx[A] =
    var hits = 0
    NodeServerConfig()
      .withHandler { req =>
        hits += 1
        req.path match
          case "/hello" =>
            Response.ok("hello")
          case "/unavailable" =>
            Response(HttpStatus.ServiceUnavailable_503).withTextContent("busy")
          case _ =>
            Response.notFound("not found")
      }
      .withPort(0)
      .startAndAwait { server =>
        body(s"http://localhost:${server.localPort}", () => hits)
      }

  test("send returns the response") {
    withServer { (url, _) =>
      Http
        .client
        .withBaseUri(url)
        .newAsyncClient
        .send(Request.get("/hello"))
        .map { resp =>
          resp.contentAsString shouldBe Some("hello")
        }
    }
  }

  test("send fails with HttpException for a 4xx response") {
    withServer { (url, _) =>
      Http
        .client
        .withBaseUri(url)
        .newAsyncClient
        .send(Request.get("/missing"))
        .map(_ => None)
        .recover { case e: HttpException =>
          Some(e)
        }
        .map { e =>
          e.flatMap(_.statusCode) shouldBe Some(404)
        }
    }
  }

  test("send fails with HttpMaxRetryException after retrying a 5xx response") {
    withServer { (url, hits) =>
      Http
        .client
        .withBaseUri(url)
        .withRetryContext(Retry.withBackOff(maxRetry = 2, initialIntervalMillis = 10))
        .newAsyncClient
        .send(Request.get("/unavailable"))
        .map(_ => None)
        .recover { case e: HttpMaxRetryException =>
          Some(e)
        }
        .map { e =>
          e.map(_.statusCode) shouldBe Some(503)
          // maxRetry bounds the total number of attempts
          e.map(_.retryContext.retryCount) shouldBe Some(2)
          hits() shouldBe 2
        }
    }
  }

end NodeHttpAsyncClientTest
