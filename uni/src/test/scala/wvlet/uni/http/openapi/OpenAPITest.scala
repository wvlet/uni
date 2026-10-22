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
package wvlet.uni.http.openapi

import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

object OpenAPITest:
  enum Role:
    case Admin,
      Member

  case class Address(city: String, zip: Option[String] = None)
  case class User(
      id: String,
      name: String,
      role: Role,
      addresses: Seq[Address],
      manager: Option[User] = None,
      labels: Map[String, String] = Map.empty
  )

  case class CreateUserRequest(name: String, role: Role)
  case class Page[A](items: Seq[A])

  object other:
    case class Address(street: String)

  class Colliding:
    @Endpoint(HttpMethod.GET, "/a")
    def a(): Address = ???

    @Endpoint(HttpMethod.GET, "/b")
    def b(): other.Address = ???

  class UserApi:
    @Endpoint(HttpMethod.GET, "/v1/users/:id", description = "Get a user by id")
    def getUser(id: String, verbose: Boolean = false): Rx[User] = ???

    @Endpoint(HttpMethod.GET, "/v1/users")
    def listUsers(limit: Int = 10, cursor: Option[String] = None): Seq[User] = ???

    @Endpoint(HttpMethod.POST, "/v1/users")
    def createUser(
        request: CreateUserRequest,
        dryRun: Boolean = false,
        httpRequest: Request
    ): User = ???

    @Endpoint(HttpMethod.PUT, "/v1/users/:id/name")
    def rename(id: String, name: String, tags: Seq[String]): Unit = ???

    @Endpoint(HttpMethod.GET, "/v1/health")
    def health(): String = "ok"

    @Endpoint(HttpMethod.GET, "/v1/events")
    def events(): Response = ???

    @Endpoint(HttpMethod.GET, "/v1/users/:id/page")
    def findUser(id: String, filter: Address, tags: Seq[String] = Seq.empty): Option[Page[User]] =
      ???

  class Duplicate:
    @Endpoint(HttpMethod.GET, "/a")
    def health(): String = "ok"

end OpenAPITest

class OpenAPITest extends UniTest:
  import OpenAPITest.*

  private lazy val doc: JSONObject =
    OpenAPI
      .of(
        Router.of[UserApi],
        OpenAPIConfig()
          .withTitle("Users")
          .withVersion("1.0")
          .withSchemaPackagePrefix("wvlet.uni.http.openapi.OpenAPITest.")
      )
      .document

  // Navigate nested objects and arrays by key or index
  private def at(v: JSONValue, path: Any*): JSONValue =
    path.foldLeft(v) {
      case (o: JSONObject, k: String) =>
        o.get(k).getOrElse(fail(s"Missing ${k} in ${o.toJSON}"))
      case (a: JSONArray, i: Int) =>
        a.v(i)
      case (other, k) =>
        fail(s"Cannot read ${k} from ${other.toJSON}")
    }

  private def str(v: JSONValue, path: Any*): String =
    at(v, path*) match
      case JSONString(s) =>
        s
      case other =>
        fail(s"Not a string: ${other.toJSON}")

  private def ref(name: String) = s"#/components/schemas/${name}"

  test("map paths, path and query parameters, and descriptions") {
    str(doc, "openapi") shouldBe "3.0.3"
    str(doc, "info", "title") shouldBe "Users"
    val get = at(doc, "paths", "/v1/users/{id}", "get")
    str(get, "operationId") shouldBe "getUser"
    str(get, "description") shouldBe "Get a user by id"
    str(get, "tags", 0) shouldBe "UserApi"
    str(get, "parameters", 0, "in") shouldBe "path"
    at(get, "parameters", 0, "required") shouldBe JSONBoolean(true)
    str(get, "parameters", 1, "name") shouldBe "verbose"
    str(get, "parameters", 1, "in") shouldBe "query"
    at(get, "parameters", 1, "required") shouldBe JSONBoolean(false)
    str(get, "responses", "200", "content", "application/json", "schema", "$ref") shouldBe
      ref("User")
    str(get, "responses", "500", "$ref") shouldBe "#/components/responses/500"
  }

  test("describe a whole-body parameter as a requestBody, next to query parameters") {
    val post = at(doc, "paths", "/v1/users", "post")
    str(post, "requestBody", "content", "application/json", "schema", "$ref") shouldBe
      ref("CreateUserRequest")
    str(post, "requestBody", "content", "application/x-msgpack", "schema", "$ref") shouldBe
      ref("CreateUserRequest")
    // The Request parameter is not part of the API
    at(post, "parameters").asInstanceOf[JSONArray].v.map(p => str(p, "name")) shouldBe Seq("dryRun")
  }

  test("describe several body parameters as fields, and Unit as 204") {
    val put    = at(doc, "paths", "/v1/users/{id}/name", "put")
    val schema = at(put, "requestBody", "content", "application/json", "schema")
    str(schema, "properties", "tags", "type") shouldBe "array"
    // A missing collection is passed as null by the router, so it is optional
    at(schema, "required") shouldBe JSONArray(IndexedSeq(JSONString("name")))
    str(put, "responses", "204", "description") shouldBe "No Content"
  }

  test("describe text, collection, and Response returns") {
    str(
      doc,
      "paths",
      "/v1/health",
      "get",
      "responses",
      "200",
      "content",
      "text/plain",
      "schema",
      "type"
    ) shouldBe "string"
    str(
      doc,
      "paths",
      "/v1/users",
      "get",
      "responses",
      "200",
      "content",
      "application/json",
      "schema",
      "items",
      "$ref"
    ) shouldBe ref("User")
    at(doc, "paths", "/v1/events", "get", "responses", "200")
      .asInstanceOf[JSONObject]
      .get("content") shouldBe None
  }

  test("put object types in components, with recursion and package prefix stripped") {
    val user = at(doc, "components", "schemas", "User")
    str(user, "properties", "manager", "$ref") shouldBe ref("User")
    str(user, "properties", "addresses", "items", "$ref") shouldBe ref("Address")
    str(user, "properties", "labels", "additionalProperties", "type") shouldBe "string"
    str(user, "properties", "role", "type") shouldBe "string"
    at(user, "required") shouldBe
      JSONArray(IndexedSeq("id", "name", "role", "addresses").map(JSONString(_)))
    str(doc, "components", "responses", "400", "description") shouldBe "Bad Request"
  }

  test("document object parameters of a GET as the body, and Option returns as 200 or 204") {
    val get = at(doc, "paths", "/v1/users/{id}/page", "get")
    at(get, "parameters").asInstanceOf[JSONArray].v.map(p => str(p, "name")) shouldBe Seq("id")
    // Two object parameters: the router reads each from the body field of its name
    val body = at(get, "requestBody", "content", "application/json", "schema")
    str(body, "properties", "filter", "$ref") shouldBe ref("Address")
    str(body, "properties", "tags", "type") shouldBe "array"
    str(get, "responses", "200", "content", "application/json", "schema", "$ref") shouldBe
      ref("Page_User_")
    str(get, "responses", "204", "description") shouldBe "No Content"
  }

  test("reject two types with the same schema name") {
    val e = intercept[IllegalArgumentException] {
      OpenAPI.of(Router.of[Colliding])
    }
    e.getMessage shouldContain "withSchemaPackagePrefix"
  }

  test("render YAML") {
    val yaml = OpenAPI.of(Router.of[UserApi]).toYAML
    yaml shouldContain "openapi: '3.0.3'"
    yaml shouldContain "/v1/users/{id}:"
  }

  test("reject duplicate operationIds") {
    val e = intercept[IllegalArgumentException] {
      OpenAPI.of(Seq(Router.of[UserApi], Router.of[Duplicate]), OpenAPIConfig())
    }
    e.getMessage shouldContain "health"
  }

end OpenAPITest
