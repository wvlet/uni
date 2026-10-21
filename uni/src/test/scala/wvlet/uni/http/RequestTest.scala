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

class RequestTest extends UniTest:

  test("should create GET request") {
    val request = Request.get("/api/users")
    request.method shouldBe HttpMethod.GET
    request.uri shouldBe "/api/users"
    request.content shouldBe HttpContent.Empty
  }

  test("should create POST request") {
    val request = Request.post("/api/users")
    request.method shouldBe HttpMethod.POST
    request.uri shouldBe "/api/users"
  }

  test("should create PUT request") {
    val request = Request.put("/api/users/1")
    request.method shouldBe HttpMethod.PUT
    request.uri shouldBe "/api/users/1"
  }

  test("should create DELETE request") {
    val request = Request.delete("/api/users/1")
    request.method shouldBe HttpMethod.DELETE
  }

  test("should create PATCH request") {
    val request = Request.patch("/api/users/1")
    request.method shouldBe HttpMethod.PATCH
  }

  test("should create HEAD request") {
    val request = Request.head("/api/users")
    request.method shouldBe HttpMethod.HEAD
  }

  test("should create OPTIONS request") {
    val request = Request.options("/api/users")
    request.method shouldBe HttpMethod.OPTIONS
  }

  test("should extract path from URI") {
    val request = Request.get("/api/users?page=1&limit=10")
    request.path shouldBe "/api/users"
  }

  test("should extract query from URI") {
    val request = Request.get("/api/users?page=1&limit=10")
    request.query shouldBe Some("page=1&limit=10")
  }

  test("should return None for query when not present") {
    val request = Request.get("/api/users")
    request.query shouldBe None
  }

  test("should add headers") {
    val request = Request
      .get("/api/users")
      .addHeader("Accept", "application/json")
      .addHeader("X-Custom", "value")
    request.headers.get("Accept") shouldBe Some("application/json")
    request.headers.get("X-Custom") shouldBe Some("value")
  }

  test("should set headers replacing existing") {
    val request = Request
      .get("/api/users")
      .setHeader("Accept", "text/html")
      .setHeader("Accept", "application/json")
    request.headers.get("Accept") shouldBe Some("application/json")
  }

  test("should remove headers") {
    val request = Request
      .get("/api/users")
      .addHeader("Accept", "application/json")
      .removeHeader("Accept")
    request.headers.contains("Accept") shouldBe false
  }

  test("should add query parameters") {
    val request = Request.get("/api/users").addQueryParam("page", "1").addQueryParam("limit", "10")
    request.getQueryParam("page") shouldBe Some("1")
    request.getQueryParam("limit") shouldBe Some("10")
  }

  test("should allow multiple values for same query parameter") {
    val request = Request
      .get("/api/users")
      .addQueryParam("tag", "scala")
      .addQueryParam("tag", "java")
    request.getQueryParams("tag") shouldBe Seq("scala", "java")
  }

  test("should set query parameter replacing existing") {
    val request = Request.get("/api/users").setQueryParam("page", "1").setQueryParam("page", "2")
    request.getQueryParam("page") shouldBe Some("2")
  }

  test("should read query parameters from the URI's query string") {
    val request = Request.get("/api/users?page=2&tag=a%20b&tag=c+d&flag&=x&empty=")
    request.path shouldBe "/api/users"
    request.getQueryParam("page") shouldBe Some("2")
    request.getQueryParams("tag") shouldBe Seq("a b", "c d")
    request.getQueryParam("flag") shouldBe Some("")
    request.getQueryParam("empty") shouldBe Some("")
    request.getQueryParam("missing") shouldBe None
    Request.get("/a?x=1#top").getQueryParam("x") shouldBe Some("1")
  }

  test("should decode UTF-8 query parameters") {
    Request.get("/q?name=%E3%81%93%E3%82%93").getQueryParam("name") shouldBe Some("こん")
  }

  test("should read a request target decoded as ISO-8859-1 as UTF-8") {
    def wire(s: String): String = String(
      s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      java.nio.charset.StandardCharsets.ISO_8859_1
    )
    Request.decodeRequestTarget(wire("/café?q=🐱")) shouldBe "/café?q=🐱"
    Request.decodeRequestTarget("/ascii?q=%E3%81%93") shouldBe "/ascii?q=%E3%81%93"
    // Already Unicode: unchanged
    Request.decodeRequestTarget("/q=🐱") shouldBe "/q=🐱"
    // Not valid UTF-8 (a lone 0xE9 byte): left as-is
    Request.decodeRequestTarget("/caf\u00e9") shouldBe "/caf\u00e9"
  }

  test("should read raw Unicode query parameters") {
    val request = Request.get("/search?q=café 🐱&tag=%F0%9F%90%B1")
    request.getQueryParam("q") shouldBe Some("café 🐱")
    request.getQueryParam("tag") shouldBe Some("🐱")
  }

  test("should list URI query parameters before added ones") {
    val request = Request.get("/api/users?tag=a").addQueryParam("tag", "b")
    request.getQueryParams("tag") shouldBe Seq("a", "b")
    request.getQueryParam("tag") shouldBe Some("a")
  }

  test("should build full URI with query parameters") {
    val request = Request.get("/api/users").addQueryParam("page", "1").addQueryParam("limit", "10")
    request.fullUri shouldContain "page=1"
    request.fullUri shouldContain "limit=10"
  }

  test("should add text content") {
    val request = Request.post("/api/users").withTextContent("Hello")
    request.content.asString shouldBe Some("Hello")
  }

  test("should add JSON content as string") {
    val request = Request.post("/api/users").withJsonContent("""{"name": "John"}""")
    request.content.asString shouldBe Some("""{"name": "John"}""")
    request.content.contentType.get.isJson shouldBe true
  }

  test("should add bytes content") {
    val bytes   = "Hello".getBytes("UTF-8")
    val request = Request.post("/api/data").withBytesContent(bytes)
    request.content.asBytes.isDefined shouldBe true
  }

  test("should set content type") {
    val request = Request.post("/api/users").withContentType(ContentType.ApplicationJson)
    request.headers.get("Content-Type") shouldBe Some("application/json")
  }

  test("should set host") {
    val request = Request.get("/api/users").withHost("example.com")
    request.host shouldBe Some("example.com")
  }

  test("should set user agent") {
    val request = Request.get("/api/users").withUserAgent("TestClient/1.0")
    request.userAgent shouldBe Some("TestClient/1.0")
  }

  test("should set accept header") {
    val request = Request.get("/api/users").withAccept("application/json")
    request.headers.get("Accept") shouldBe Some("application/json")
  }

  test("should set authorization header") {
    val request = Request.get("/api/users").withAuthorization("Bearer token123")
    request.headers.get("Authorization") shouldBe Some("Bearer token123")
  }

  test("should set bearer token") {
    val request = Request.get("/api/users").withBearerToken("token123")
    request.headers.get("Authorization") shouldBe Some("Bearer token123")
  }

  test("should set basic auth") {
    val request = Request.get("/api/users").withBasicAuth("user", "pass")
    val auth    = request.headers.get("Authorization").get
    auth.startsWith("Basic ") shouldBe true
  }

  test("should copy with new method") {
    val request  = Request.get("/api/users")
    val modified = request.withMethod(HttpMethod.POST)
    modified.method shouldBe HttpMethod.POST
    request.method shouldBe HttpMethod.GET // original unchanged
  }

  test("should copy with new URI") {
    val request  = Request.get("/api/users")
    val modified = request.withUri("/api/posts")
    modified.uri shouldBe "/api/posts"
    request.uri shouldBe "/api/users" // original unchanged
  }

end RequestTest
