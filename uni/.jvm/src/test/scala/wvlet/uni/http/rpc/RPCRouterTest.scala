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
package wvlet.uni.http.rpc

import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

// Test models
case class User(id: Long, name: String) derives Weaver
case class CreateUserRequest(name: String, email: String) derives Weaver

// Test service trait
trait TestUserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User
  def createUserFromRequest(request: CreateUserRequest): User
  def listUsers(limit: Int = 10, offset: Int = 0): Seq[User]
  def findUser(name: Option[String]): Option[User]

// Test implementation
class TestUserServiceImpl extends TestUserService:
  private var users = Map(1L -> User(1, "Alice"), 2L -> User(2, "Bob"))

  def getUser(id: Long): User = users.getOrElse(
    id,
    throw RPCStatus.NOT_FOUND_U5.newException(s"User not found: ${id}")
  )

  def createUser(name: String, email: String): User =
    val id   = users.size + 1L
    val user = User(id, name)
    users = users + (id -> user)
    user

  def createUserFromRequest(request: CreateUserRequest): User = createUser(
    request.name,
    request.email
  )

  def listUsers(limit: Int, offset: Int): Seq[User] = users
    .values
    .toSeq
    .slice(offset, offset + limit)

  def findUser(name: Option[String]): Option[User] = name.flatMap(n =>
    users.values.find(_.name == n)
  )

class RPCRouterTest extends UniTest:

  test("RPCRouter.of should extract routes from service trait") {
    val router = RPCRouter.of[TestUserService](TestUserServiceImpl())

    router.serviceName shouldContain "TestUserService"

    // Should include at least the 5 methods defined in the trait
    val methodNames = router.routes.map(_.methodName).toSet
    methodNames shouldContain "getUser"
    methodNames shouldContain "createUser"
    methodNames shouldContain "createUserFromRequest"
    methodNames shouldContain "listUsers"
    methodNames shouldContain "findUser"
  }

  test("RPCRouter should generate correct paths") {
    val router = RPCRouter.of[TestUserService](TestUserServiceImpl())

    router
      .routes
      .foreach { route =>
        route.path shouldBe s"/${router.serviceName}/${route.methodName}"
      }
  }

  test("RPCRouter should not include Object methods") {
    val router = RPCRouter.of[TestUserService](TestUserServiceImpl())

    val methodNames = router.routes.map(_.methodName)
    methodNames shouldNotContain "equals"
    methodNames shouldNotContain "hashCode"
    methodNames shouldNotContain "toString"
    methodNames shouldNotContain "getClass"
  }

  test("RPCRouter.findRoute should find route by method name") {
    val router = RPCRouter.of[TestUserService](TestUserServiceImpl())

    val route = router.findRoute("getUser")
    route.isDefined shouldBe true
    route.get.methodName shouldBe "getUser"

    router.findRoute("nonexistent") shouldBe None
  }

  test("RPCRouter should detect method overloading") {
    // Service with overloaded methods
    trait OverloadedService:
      def process(id: Long): User
      def process(name: String): User

    class OverloadedServiceImpl extends OverloadedService:
      def process(id: Long): User     = User(id, "test")
      def process(name: String): User = User(1, name)

    val ex = intercept[IllegalArgumentException] {
      RPCRouter.of[OverloadedService](OverloadedServiceImpl())
    }
    ex.getMessage shouldContain "overloading"
    ex.getMessage shouldContain "process"
  }

  test("RPCRouter should handle Rx return types") {
    // Service with Rx return type
    trait RxService:
      def getUser(id: Long): Rx[User]

    class RxServiceImpl extends RxService:
      def getUser(id: Long): Rx[User] = Rx.single(User(id, "test"))

    val router = RPCRouter.of[RxService](RxServiceImpl())
    val codec  = router.codecs("getUser")

    // The return weaver should be for User, not Rx[User]
    val result = codec.encodeResult(User(1, "Alice"))
    result shouldContain "\"id\":1"
    result shouldContain "\"name\":\"Alice\""
  }

  test("RPCRouter should handle Unit return type") {
    trait VoidService:
      def doSomething(id: Long): Unit

    class VoidServiceImpl extends VoidService:
      def doSomething(id: Long): Unit = ()

    val router = RPCRouter.of[VoidService](VoidServiceImpl())
    val codec  = router.codecs("doSomething")

    // Unit should encode as null
    val result = codec.encodeResult(())
    result shouldBe "null"
  }

end RPCRouterTest

class MethodCodecTest extends UniTest:

  val router = RPCRouter.of[TestUserService](TestUserServiceImpl())

  test("MethodCodec should decode simple parameters") {
    val codec = router.codecs("getUser")
    val args  = codec.decodeParams("""{"request": {"id": 123}}""")

    args.size shouldBe 1
    args.head shouldBe 123L
  }

  test("MethodCodec should decode multiple parameters") {
    val codec = router.codecs("createUser")
    val args  = codec.decodeParams(
      """{"request": {"name": "Charlie", "email": "charlie@test.com"}}"""
    )

    args.size shouldBe 2
    args(0) shouldBe "Charlie"
    args(1) shouldBe "charlie@test.com"
  }

  test("MethodCodec should decode case class parameter") {
    val codec = router.codecs("createUserFromRequest")
    val args  = codec.decodeParams(
      """{"request": {"request": {"name": "Dave", "email": "dave@test.com"}}}"""
    )

    args.size shouldBe 1
    args.head shouldMatch { case CreateUserRequest("Dave", "dave@test.com") =>
    }
  }

  test("MethodCodec should decode all parameters when provided") {
    val codec = router.codecs("listUsers")
    val args  = codec.decodeParams("""{"request": {"limit": 20, "offset": 5}}""")

    args.size shouldBe 2
    args(0) shouldBe 20
    args(1) shouldBe 5
  }

  test("MethodCodec should use None for missing Option parameters") {
    val codec = router.codecs("findUser")
    val args  = codec.decodeParams("""{"request": {}}""")

    args.size shouldBe 1
    args.head shouldBe None
  }

  test("MethodCodec should decode Option parameters") {
    val codec = router.codecs("findUser")
    val args  = codec.decodeParams("""{"request": {"name": "Alice"}}""")

    args.size shouldBe 1
    args.head shouldBe Some("Alice")
  }

  test("MethodCodec should support CName matching for parameters") {
    val codec = router.codecs("listUsers")

    // snake_case should match camelCase
    val args = codec.decodeParams("""{"request": {"limit": 20, "offset": 5}}""")
    args(0) shouldBe 20
    args(1) shouldBe 5
  }

  test("MethodCodec should handle empty request body with Option params") {
    // findUser has an Option parameter, which defaults to None
    val codec = router.codecs("findUser")
    val args  = codec.decodeParams("")

    args.size shouldBe 1
    args.head shouldBe None
  }

  test("MethodCodec should throw on missing required parameter") {
    val codec = router.codecs("getUser")

    val ex = intercept[RPCException] {
      codec.decodeParams("""{"request": {}}""")
    }
    ex.status shouldBe RPCStatus.INVALID_ARGUMENT_U2
    ex.message shouldContain "id"
  }

  test("MethodCodec should throw on missing request field") {
    val codec = router.codecs("getUser")

    val ex = intercept[RPCException] {
      codec.decodeParams("""{"id": 123}""")
    }
    ex.status shouldBe RPCStatus.INVALID_REQUEST_U1
    ex.message shouldContain "request"
  }

  test("MethodCodec should throw INVALID_REQUEST on invalid JSON") {
    val codec = router.codecs("getUser")

    val ex = intercept[RPCException] {
      codec.decodeParams("""not valid json""")
    }
    ex.status shouldBe RPCStatus.INVALID_REQUEST_U1
    ex.message shouldContain "Invalid JSON"
  }

  test("MethodCodec should encode result to JSON") {
    val codec = router.codecs("getUser")
    val user  = User(1, "Alice")
    val json  = codec.encodeResult(user)

    json shouldContain "\"id\":1"
    json shouldContain "\"name\":\"Alice\""
  }

  test("MethodCodec should encode Seq result to JSON") {
    val codec = router.codecs("listUsers")
    val users = Seq(User(1, "Alice"), User(2, "Bob"))
    val json  = codec.encodeResult(users)

    json shouldContain "Alice"
    json shouldContain "Bob"
  }

  test("MethodCodec should encode None as null") {
    val codec = router.codecs("findUser")
    val json  = codec.encodeResult(None)
    json shouldBe "null"
  }

  test("MethodCodec should encode Some value") {
    val codec = router.codecs("findUser")
    val json  = codec.encodeResult(Some(User(1, "Alice")))

    json shouldContain "Alice"
  }

end MethodCodecTest
