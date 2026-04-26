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
import wvlet.uni.http.rpc.RPC
import wvlet.uni.test.UniTest

object RxRouterTestFixtures:

  @RPC
  trait FrontendApi:
    def status: String
    def submitQuery(request: String): String

  object FrontendApi extends RxRouterProvider:
    override def router: RxRouter = RxRouter.of[FrontendApi]

  @RPC(path = "/v1")
  trait FileApi:
    def upload(name: String): String
    def download(id: String): Array[Byte]

  trait NotAnRpcTrait:
    @Endpoint(HttpMethod.GET, "/health")
    def health: String

end RxRouterTestFixtures

class RxRouterTest extends UniTest:
  import RxRouterTestFixtures.*

  test("RxRouter.of[T] exposes every public method when T is @RPC-annotated") {
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

  test("RxRouter.of[T] honors @RPC(path = ...) prefix") {
    val router = RxRouter.of[FileApi]
    router.toRoutes.map(_.pathPattern).toSet shouldBe
      Set(
        "/v1/wvlet.uni.http.router.RxRouterTestFixtures.FileApi/upload",
        "/v1/wvlet.uni.http.router.RxRouterTestFixtures.FileApi/download"
      )
  }

  test("RxRouter.of[T] falls back to @Endpoint scan when @RPC is absent") {
    val router = RxRouter.of[NotAnRpcTrait]
    val routes = router.toRoutes
    routes.size shouldBe 1
    routes.head.method shouldBe HttpMethod.GET
    routes.head.pathPattern shouldBe "/health"
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

end RxRouterTest
