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

class HttpServerSecurityTest extends UniTest:

  private def upgrade(origin: Option[String], host: String = "example.com:8080"): Request =
    val req = Request.get("/ws").addHeader(HttpHeader.Host, host)
    origin.fold(req)(o => req.addHeader(HttpHeader.Origin, o))

  test("hide exception messages unless detailed errors are enabled") {
    val e       = RuntimeException("jdbc:postgresql://db.internal/secret")
    val generic = HttpServerSecurity.errorResponse(e, detailedErrors = false)
    generic.status shouldBe HttpStatus.InternalServerError_500
    generic.contentAsString shouldBe Some(HttpServerSecurity.InternalServerErrorMessage)

    val detailed = HttpServerSecurity.errorResponse(e, detailedErrors = true)
    detailed.status shouldBe HttpStatus.InternalServerError_500
    detailed.contentAsString.get shouldContain "db.internal"
  }

  test("allow a WebSocket upgrade without an Origin header") {
    HttpServerSecurity.checkWebSocketOrigin(upgrade(None), Nil) shouldBe None
  }

  test("allow a same-origin WebSocket upgrade") {
    HttpServerSecurity.checkWebSocketOrigin(upgrade(Some("http://example.com:8080")), Nil) shouldBe
      None
    // Default ports are equivalent to an omitted port
    HttpServerSecurity.checkWebSocketOrigin(
      upgrade(Some("https://example.com"), host = "example.com:443"),
      Nil
    ) shouldBe None
    HttpServerSecurity.checkWebSocketOrigin(
      upgrade(Some("HTTP://Example.com:80/"), host = "example.com"),
      Nil
    ) shouldBe None
  }

  test("reject a cross-origin WebSocket upgrade") {
    val rejection = HttpServerSecurity.checkWebSocketOrigin(
      upgrade(Some("https://evil.example")),
      Nil
    )
    rejection.map(_.status) shouldBe Some(HttpStatus.Forbidden_403)
    // A different port is a different origin
    HttpServerSecurity
      .checkWebSocketOrigin(upgrade(Some("http://example.com:5173")), Nil)
      .map(_.status) shouldBe Some(HttpStatus.Forbidden_403)
    // An opaque origin (sandboxed iframe, file://) is never same-origin
    HttpServerSecurity.checkWebSocketOrigin(upgrade(Some("null")), Nil).map(_.status) shouldBe
      Some(HttpStatus.Forbidden_403)
  }

  test("allow listed origins and the wildcard") {
    val req = upgrade(Some("http://localhost:5173"))
    HttpServerSecurity.checkWebSocketOrigin(req, Seq("http://localhost:5173")) shouldBe None
    HttpServerSecurity.checkWebSocketOrigin(req, Seq("HTTP://LOCALHOST:5173/")) shouldBe None
    HttpServerSecurity.checkWebSocketOrigin(req, Seq("*")) shouldBe None
    HttpServerSecurity.checkWebSocketOrigin(req, Seq("http://localhost:3000")).isDefined shouldBe
      true
  }

  test("WebSocketHandshake.validate checks the origin first") {
    val req = upgrade(Some("https://evil.example"))
      .addHeader(HttpHeader.SecWebSocketKey, "dGhlIHNhbXBsZSBub25jZQ==")
      .addHeader(HttpHeader.SecWebSocketVersion, "13")
    WebSocketHandshake.validate(req, Nil) shouldMatch { case Left(r: Response) =>
      r.status shouldBe HttpStatus.Forbidden_403
    }
    WebSocketHandshake.validate(req, Seq("https://evil.example")) shouldBe
      Right("dGhlIHNhbXBsZSBub25jZQ==")
  }

  test("reject CR/LF in response headers") {
    intercept[IllegalArgumentException] {
      Response.ok.addHeader(HttpHeader.Location, "/next\r\nSet-Cookie: session=evil")
    }
    intercept[IllegalArgumentException] {
      Response.ok.setHeader("X-Bad\nName", "v")
    }
    intercept[IllegalArgumentException] {
      HttpServerSecurity.validateHeaders(HttpMultiMap("X-Value" -> "a\u0000b"))
    }
    intercept[IllegalArgumentException] {
      Request.get("/").addHeader("X-Forwarded-For", "1.2.3.4\r\nHost: evil")
    }
    Response.ok.addHeader(HttpHeader.Location, "/next?a=1&b=2").location shouldBe
      Some("/next?a=1&b=2")
  }

  private def hostRequest(host: String): Request = Request.get("/").addHeader(HttpHeader.Host, host)

  test("allow only loopback host names on a loopback server by default") {
    val allowed = HttpServerSecurity.defaultAllowedHosts("127.0.0.1")
    Seq("localhost:8080", "LOCALHOST", "127.0.0.1:8080", "[::1]:8080", "app.localhost:3000")
      .foreach { host =>
        HttpServerSecurity.checkHost(hostRequest(host), allowed) shouldBe None
      }
    Seq("evil.example:8080", "evil.example", "localhost.evil.example", "127.0.0.1.nip.io").foreach {
      host =>
        val rejection = HttpServerSecurity.checkHost(hostRequest(host), allowed)
        rejection.map(_.status) shouldBe Some(HttpStatus.Forbidden_403)
    }
  }

  test("allow every host on a non-loopback server by default") {
    HttpServerSecurity.defaultAllowedHosts("0.0.0.0") shouldBe Seq("*")
    HttpServerSecurity.defaultAllowedHosts("localhost") shouldBe HttpServerSecurity.LoopbackHosts
    HttpServerSecurity.defaultAllowedHosts("::1") shouldBe HttpServerSecurity.LoopbackHosts
    HttpServerSecurity.defaultAllowedHosts("[0:0:0:0:0:0:0:1]") shouldBe
      HttpServerSecurity.LoopbackHosts
    HttpServerSecurity.defaultAllowedHosts("::2") shouldBe Seq("*")
  }

  test("match exact and wildcard host entries, ignoring the port") {
    val allowed = Seq("api.example.com", "*.internal.example")
    HttpServerSecurity.isHostAllowed("api.example.com:443", allowed) shouldBe true
    HttpServerSecurity.isHostAllowed("API.Example.Com.", allowed) shouldBe true
    HttpServerSecurity.isHostAllowed("a.internal.example:8080", allowed) shouldBe true
    HttpServerSecurity.isHostAllowed("internal.example", allowed) shouldBe false
    HttpServerSecurity.isHostAllowed("evilinternal.example", allowed) shouldBe false
    HttpServerSecurity.isHostAllowed("other.example.com", allowed) shouldBe false
    HttpServerSecurity.isHostAllowed("anything", Seq("*")) shouldBe true
  }

  test("allow a request without a Host header") {
    HttpServerSecurity.checkHost(Request.get("/"), HttpServerSecurity.LoopbackHosts) shouldBe None
  }

end HttpServerSecurityTest
