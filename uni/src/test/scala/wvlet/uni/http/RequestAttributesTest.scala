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

class RequestAttributesTest extends UniTest:
  private val Identity = RequestAttribute[String]("identity")
  private val Retries  = RequestAttribute[Int]("retries")

  test("set, read, and remove attributes") {
    val r = Request.get("/").withAttribute(Identity, "alice").withAttribute(Retries, 2)
    r.attribute(Identity) shouldBe Some("alice")
    r.attribute(Retries) shouldBe Some(2)
    r.withoutAttribute(Identity).attribute(Identity) shouldBe None
    Request.get("/").attributes.isEmpty shouldBe true
  }

  test("compare keys by identity, not by name") {
    val other = RequestAttribute[String]("identity")
    val r     = Request.get("/").withAttribute(Identity, "alice")
    r.attribute(other) shouldBe None
  }

  test("not print attribute values") {
    val r = Request.get("/").withAttribute(Identity, "secret-token")
    r.attributes.toString shouldBe "RequestAttributes(identity)"
    r.toString.contains("secret-token") shouldBe false
  }

  test("compare attributes by content") {
    Request.get("/").withAttribute(Identity, "a") shouldBe
      Request.get("/").withAttribute(Identity, "a")
    Request.get("/").withAttribute(Identity, "a") shouldNotBe Request.get("/")
  }

  test("never send attributes on the wire") {
    val r = Request.get("/").withAttribute(Identity, "alice")
    r.wireHeaders shouldBe Request.get("/").wireHeaders
  }

end RequestAttributesTest
