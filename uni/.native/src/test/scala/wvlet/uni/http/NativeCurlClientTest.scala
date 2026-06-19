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
  * Verifies the Scala Native libcurl client ([[CurlChannel]]) against the in-process Native server
  * over loopback. Exercises the variadic `curl_easy_setopt`/`curl_easy_getinfo` paths (URL, write
  * callback, response code, POST fields, header list) — all previously broken on arm64 because the
  * variadic curl functions were declared as fixed-arity externs.
  */
class NativeCurlClientTest extends UniTest:

  private def client(server: HttpServer) =
    Http.client.withBaseUri(s"http://127.0.0.1:${server.localPort}").newSyncClient

  test("curl client performs a loopback GET") {
    NativeServer
      .withHandler(req => Response.ok(s"Hello from ${req.path}"))
      .withPort(0)
      .start { server =>
        val response = client(server).send(Request.get("/test"))
        response.statusCode shouldBe 200
        response.contentAsString shouldBe Some("Hello from /test")
      }
  }

  test("curl client sends a POST body") {
    NativeServer
      .withHandler(req => Response.ok(s"Received: ${req.content.toContentString}"))
      .withPort(0)
      .start { server =>
        val response = client(server).send(
          Request.post("/data").withContent(HttpContent.text("payload"))
        )
        response.statusCode shouldBe 200
        response.contentAsString shouldBe Some("Received: payload")
      }
  }

  test("curl client round-trips request and response headers") {
    NativeServer
      .withHandler(req =>
        Response.ok("ok").addHeader("X-Echoed", req.header("X-Echo").getOrElse("none"))
      )
      .withPort(0)
      .start { server =>
        val response = client(server).send(Request.get("/headers").addHeader("X-Echo", "ping"))
        response.statusCode shouldBe 200
        response.header("X-Echoed") shouldBe Some("ping")
      }
  }

end NativeCurlClientTest
