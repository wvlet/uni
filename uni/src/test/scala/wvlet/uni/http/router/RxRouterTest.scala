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

import wvlet.uni.http.HttpMethod
import wvlet.uni.test.UniTest

object RxRouterTestFixtures:

  trait FrontendApi:
    def status: String
    def submitQuery(request: String): String

  object FrontendApi extends RxRouterProvider:
    override def router: RxRouter = RxRouter.of[FrontendApi]

  trait FileApi:
    def upload(name: String): String
    def download(id: String): Array[Byte]

  // For the overload-rejection test: two methods with the same name.
  trait OverloadedApi:
    def hello(name: String): String
    def hello(name: String, greeting: String): String

  // For the Object/Product filter test: a class (not just a trait) so toString/hashCode/equals
  // are visible on the receiver. Surface.methodsOf already filters Object/Product methods, but
  // we also apply a defensive name-based filter in the macro.
  class WithObjectMethods:
    def greet(name: String): String = s"hi ${name}"

end RxRouterTestFixtures

class RxRouterTest extends UniTest:
  import RxRouterTestFixtures.*

  test("RxRouter.of[T] exposes every public method as a POST endpoint") {
    val router = RxRouter.of[FrontendApi]
    router.isLeaf shouldBe true
    val routes = router.toRoutes
    routes.size shouldBe 2
    routes.map(_.methodSurface.name).toSet shouldBe Set("status", "submitQuery")
    routes.foreach { r =>
      r.method shouldBe HttpMethod.POST
    }
  }

  test("RxRouter.of[T] uses controller fullName as the path namespace by default") {
    val router = RxRouter.of[FrontendApi]
    router.toRoutes.map(_.pathPattern).toSet shouldBe
      Set(
        "/wvlet.uni.http.router.RxRouterTestFixtures.FrontendApi/status",
        "/wvlet.uni.http.router.RxRouterTestFixtures.FrontendApi/submitQuery"
      )
  }

  test("withPathPrefix prepends the namespace to every endpoint") {
    val router = RxRouter.of[FileApi].withPathPrefix("/v1")
    router.toRoutes.map(_.pathPattern).toSet shouldBe
      Set(
        "/v1/wvlet.uni.http.router.RxRouterTestFixtures.FileApi/upload",
        "/v1/wvlet.uni.http.router.RxRouterTestFixtures.FileApi/download"
      )
  }

  test("withPathPrefix strips trailing slashes") {
    val router = RxRouter.of[FileApi].withPathPrefix("/v1/")
    router.toRoutes.head.pathPattern.startsWith("/v1/wvlet.uni.") shouldBe true
  }

  test("withPathPrefix on a stem propagates to all children") {
    val combined = RxRouter
      .of(RxRouter.of[FrontendApi], RxRouter.of[FileApi])
      .withPathPrefix("/api")
    combined
      .toRoutes
      .foreach { r =>
        r.pathPattern.startsWith("/api/") shouldBe true
      }
  }

  test("withPathPrefix composes additively rather than overwriting") {
    val v1    = RxRouter.of[FileApi].withPathPrefix("/v1")
    val v2    = RxRouter.of[FrontendApi].withPathPrefix("/v2")
    val all   = RxRouter.of(v1, v2).withPathPrefix("/api")
    val paths = all.toRoutes.map(_.pathPattern).toSet
    // The stem-level "/api" is prepended to each child's pre-existing prefix.
    paths shouldContain "/api/v1/wvlet.uni.http.router.RxRouterTestFixtures.FileApi/upload"
    paths shouldContain "/api/v2/wvlet.uni.http.router.RxRouterTestFixtures.FrontendApi/status"
  }

  test("RxRouter.of(routers*) composes child routers") {
    val combined = RxRouter.of(RxRouter.of[FrontendApi], RxRouter.of[FileApi])
    combined.isLeaf shouldBe false
    combined.toRoutes.size shouldBe 4
    combined.children.size shouldBe 2
  }

  test("RxRouterProvider exposes the default router for an RPC interface") {
    FrontendApi.router shouldMatch { case _: RxRouter =>
    }
    FrontendApi.router.toRoutes.size shouldBe 2
  }

  test("RxRouter.of[T] rejects overloaded methods") {
    val router = RxRouter.of[OverloadedApi]
    val ex     = intercept[IllegalArgumentException] {
      router.toRoutes
    }
    ex.getMessage.contains("Overloaded RPC methods are not supported") shouldBe true
    ex.getMessage.contains("hello") shouldBe true
  }

  test("RxRouter.of[T] does not expose Any / Object / Product methods") {
    val router = RxRouter.of[WithObjectMethods]
    val names  = router.toRoutes.map(_.methodSurface.name).toSet
    names shouldBe Set("greet")
    names.contains("toString") shouldBe false
    names.contains("hashCode") shouldBe false
    names.contains("equals") shouldBe false
    names.contains("getClass") shouldBe false
  }

  test("StemNode names include a stem- prefix for log readability") {
    val combined = RxRouter.of(RxRouter.of[FrontendApi], RxRouter.of[FileApi])
    combined.name.startsWith("stem-") shouldBe true
  }

end RxRouterTest
