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
package wvlet.uni.http.router

import wvlet.uni.http.{HttpMethod, Request, Response}
import wvlet.uni.test.UniTest

// Single test controller to minimize Surface memory usage
class TestController:
  @Endpoint(HttpMethod.GET, "/users")
  def listUsers(): String = "user list"

  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: String): String = s"user ${id}"

  @Endpoint(HttpMethod.GET, "/search")
  def search(query: String, limit: Int = 10): String = s"query=${query}, limit=${limit}"

class PublicController:
  @Endpoint(HttpMethod.GET, "/public")
  def publicEndpoint(): String = "public"

class RouterTest extends UniTest:
  // Single shared router instance
  private lazy val router = Router.of[TestController]

  test("PathComponent should parse paths") {
    // Simple path
    PathComponent.parse("/users").size shouldBe 1

    // Path with parameter
    val withParam = PathComponent.parse("/users/:id")
    withParam.size shouldBe 2
    withParam(1) shouldMatch { case PathComponent.Parameter("id") =>
    }

    // Empty path
    PathComponent.parse("/").size shouldBe 0
  }

  test("Router.of should extract routes") {
    router.routes.size shouldBe 3

    // Verify routes are extracted correctly
    val getUsers = router.routes.find(_.pathPattern == "/users")
    getUsers.isDefined shouldBe true
    getUsers.get.method shouldBe HttpMethod.GET
  }

  test("RouteMatcher should match and extract params") {
    val matcher = RouteMatcher(router.routes)

    // Match exact path
    val usersMatch = matcher.findRoute(Request.get("/users"))
    usersMatch.isDefined shouldBe true
    usersMatch.get.pathParams.isEmpty shouldBe true

    // Match with path param
    val userMatch = matcher.findRoute(Request.get("/users/123"))
    userMatch.isDefined shouldBe true
    userMatch.get.pathParams shouldBe Map("id" -> "123")

    // No match for unknown path
    matcher.findRoute(Request.get("/unknown")).isEmpty shouldBe true
  }

  test("HttpRequestMapper should bind parameters") {
    val matcher = RouteMatcher(router.routes)
    val mapper  = HttpRequestMapper()

    // Bind query params
    val request = Request.get("/search").setQueryParam("query", "test").setQueryParam("limit", "20")
    val routeMatch = matcher.findRoute(request).get
    val args = mapper.bindParameters(request, routeMatch.route.methodSurface, routeMatch.pathParams)

    args.size shouldBe 2
    args(0) shouldBe "test"
    args(1) shouldBe 20
  }

  test("HttpRequestMapper should report a malformed parameter as a mapping error") {
    val matcher = RouteMatcher(router.routes)
    val mapper  = HttpRequestMapper()
    val request = Request.get("/search").setQueryParam("query", "q").setQueryParam("limit", "abc")
    val routeMatch = matcher.findRoute(request).get
    val e          = intercept[HttpRequestMappingException] {
      mapper.bindParameters(request, routeMatch.route.methodSurface, routeMatch.pathParams)
    }
    e.getMessage shouldContain "limit"
    // The rejected value is not echoed back
    e.getMessage.contains("abc") shouldBe false
  }

  test("ControllerProvider should provide instances") {
    val provider   = SimpleControllerProvider()
    val controller = provider.get(router.routes.head.controllerSurface)
    controller.isInstanceOf[TestController] shouldBe true
  }

  test("Router.filter should add filter") {
    class TestFilter
    val filtered = Router.filter[TestFilter].andThen(router)
    filtered.routes.forall(_.filterSurfaces.map(_.name) == Seq("TestFilter")) shouldBe true
    Router.commonFilters(filtered.routes).map(_.name) shouldBe Seq("TestFilter")
  }

  test("Router.andThen should keep each side's filters on its own routes") {
    class AuthFilter
    val combined = Router.of[PublicController].andThen(Router.filter[AuthFilter].andThen(router))
    combined.routes.find(_.pathPattern == "/public").get.filterSurfaces shouldBe Seq.empty
    combined
      .routes
      .filter(_.controllerSurface.name == "TestController")
      .forall(_.filterSurfaces.map(_.name) == Seq("AuthFilter")) shouldBe true
    // No filter is shared by every route
    Router.commonFilters(combined.routes) shouldBe Seq.empty
  }

  test("Router.filter should keep inner filters inside the outer one") {
    class OuterFilter
    class InnerFilter
    val nested = Router
      .filter[OuterFilter]
      .andThen(Router.of[PublicController].andThen(Router.filter[InnerFilter].andThen(router)))
    nested.routes.find(_.pathPattern == "/public").get.filterSurfaces.map(_.name) shouldBe
      Seq("OuterFilter")
    nested.routes.find(_.pathPattern == "/users").get.filterSurfaces.map(_.name) shouldBe
      Seq("OuterFilter", "InnerFilter")
    Router.commonFilters(nested.routes).map(_.name) shouldBe Seq("OuterFilter")
  }

  test("Surface.methodsOf should cache MethodSurface instances") {
    import wvlet.uni.surface.Surface

    // Call methodsOf twice for the SAME type
    val methods1 = Surface.methodsOf[TestController]
    val methods2 = Surface.methodsOf[TestController]

    // Verify caching works - same instances should be returned
    (methods1 eq methods2) shouldBe true

    // Individual method surfaces should also be the same
    if methods1.nonEmpty && methods2.nonEmpty then
      (methods1.head eq methods2.head) shouldBe true

    // Cache should contain the entry
    Surface.methodSurfaceCache.contains("wvlet.uni.http.router.TestController") shouldBe true
  }

  test("Surface.methodsOf should cache generic types separately") {
    import wvlet.uni.surface.Surface

    // Generic class with a method that returns the type parameter
    class Box[T]:
      def get: T = ???

    // Get method surfaces for different type arguments
    val intMethods    = Surface.methodsOf[Box[Int]]
    val stringMethods = Surface.methodsOf[Box[String]]

    // They should be cached separately (different cache keys)
    (intMethods eq stringMethods) shouldBe false

    // Each should have correct return type
    val intGet    = intMethods.find(_.name == "get")
    val stringGet = stringMethods.find(_.name == "get")

    intGet.isDefined shouldBe true
    stringGet.isDefined shouldBe true

    // Return types should be different
    intGet.get.returnType.name shouldBe "Int"
    stringGet.get.returnType.name shouldBe "String"

    // Verify both are cached with full type names including type args
    Surface.methodSurfaceCache.keys.exists(_.contains("Box[Int]")) shouldBe true
    Surface.methodSurfaceCache.keys.exists(_.contains("Box[String]")) shouldBe true
  }

end RouterTest
