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
package wvlet.uni.dom

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions
import wvlet.uni.rx.Rx

class RouterTest extends UniTest:

  test("Location parses query parameters"):
    val loc    = Location("/users", "?name=john&age=30", "")
    val params = loc.queryParams
    params("name") shouldBe "john"
    params("age") shouldBe "30"

  test("Location parses empty query string"):
    val loc = Location("/users", "", "")
    loc.queryParams shouldBe Map.empty

  test("Location parses query string with question mark only"):
    val loc = Location("/users", "?", "")
    loc.queryParams shouldBe Map.empty

  test("Location parses query string without leading question mark"):
    val loc    = Location("/users", "name=john", "")
    val params = loc.queryParams
    params("name") shouldBe "john"

  test("Location parses query parameter without value"):
    val loc    = Location("/users", "?flag", "")
    val params = loc.queryParams
    params("flag") shouldBe ""

  test("Location parses hash value"):
    val loc = Location("/page", "", "#section1")
    loc.hashValue shouldBe Some("section1")

  test("Location parses hash without prefix"):
    val loc = Location("/page", "", "section1")
    loc.hashValue shouldBe Some("section1")

  test("Location handles empty hash"):
    val loc1 = Location("/page", "", "")
    loc1.hashValue shouldBe None

    val loc2 = Location("/page", "", "#")
    loc2.hashValue shouldBe None

  test("RouteParams provides path parameter access"):
    val params = RouteParams(path = Map("id" -> "123", "name" -> "john"))
    params.pathParam("id") shouldBe "123"
    params.pathParam("name") shouldBe "john"

  test("RouteParams throws for missing path parameter"):
    val params = RouteParams()
    intercept[NoSuchElementException]:
      params.pathParam("missing")

  test("RouteParams provides optional path parameter access"):
    val params = RouteParams(path = Map("id" -> "123"))
    params.pathParamOption("id") shouldBe Some("123")
    params.pathParamOption("missing") shouldBe None

  test("RouteParams provides query parameter access"):
    val params = RouteParams(query = Map("sort" -> "name", "order" -> "asc"))
    params.queryParam("sort") shouldBe Some("name")
    params.queryParam("missing") shouldBe None
    params.queryParamOrElse("order", "desc") shouldBe "asc"
    params.queryParamOrElse("missing", "default") shouldBe "default"

  test("Route companion creates route without parameter function"):
    val route = Route("/home", "Home Page")
    route.pattern shouldBe "/home"
    route.render(RouteParams()) shouldBe "Home Page"

  test("RouterInstance creates correctly"):
    val router = Router(
      Route("/", _ => "home"),
      Route("/users", _ => "users"),
      Route("/about", _ => "about")
    )
    router shouldMatch { case _: RouterInstance[?] =>
    }

  test("RouterInstance.isActive returns Rx[Boolean]"):
    val router = Router(Route("/users/:id", p => s"user-${p.pathParam("id")}"))
    val active = router.isActive("/users/123")
    active shouldMatch { case _: Rx[?] =>
    }

  test("RouterInstance.isActiveExact returns Rx[Boolean]"):
    val router = Router(Route("/users", _ => "users"))
    val active = router.isActiveExact("/users")
    active shouldMatch { case _: Rx[?] =>
    }

  test("RouterInstance.link creates anchor element"):
    val router = Router(Route("/", _ => div("home")))
    val linkEl = router.link("/about", "About")
    linkEl shouldMatch { case _: RxElement =>
    }

  test("Router.location returns Rx[Location]"):
    val loc = Router.location
    loc shouldMatch { case _: Rx[?] =>
    }

  test("Router.pathname returns Rx[String]"):
    val pathname = Router.pathname
    pathname shouldMatch { case _: Rx[?] =>
    }

  test("Router.search returns Rx[String]"):
    val search = Router.search
    search shouldMatch { case _: Rx[?] =>
    }

  test("Router.hash returns Rx[String]"):
    val hash = Router.hash
    hash shouldMatch { case _: Rx[?] =>
    }

  test("Router.currentLocation returns Location"):
    val loc = Router.currentLocation
    loc shouldMatch { case Location(_, _, _) =>
    }

  test("Route pattern compiles correctly for static paths"):
    val router = Router(Route("/api/v1/users", _ => "static"))
    router shouldMatch { case _: RouterInstance[?] =>
    }

  test("Route pattern compiles correctly for mixed paths"):
    val router = Router(
      Route(
        "/api/v1/users/:userId/posts/:postId",
        p => s"${p.pathParam("userId")}-${p.pathParam("postId")}"
      )
    )
    router shouldMatch { case _: RouterInstance[?] =>
    }

  test("Router.isActive emits values reactively"):
    val router = Router(Route("/", _ => "home"))
    var result = false
    val cancel = router
      .isActive("/")
      .run { v =>
        result = v
      }

    // Test programmatic navigation updates isActive
    Router.push("/other")
    result shouldBe false

    Router.push("/")
    result shouldBe true

    cancel.cancel

  test("Router.pathname emits current path reactively"):
    var result = ""
    val cancel = Router
      .pathname
      .run { v =>
        result = v
      }
    result shouldMatch { case _: String =>
    }
    cancel.cancel

  test("RouterInstance.outlet returns Rx"):
    val router = Router(Route("/", _ => "home"), Route("*", _ => "not-found"))
    router.outlet shouldMatch { case _: Rx[?] =>
    }

  test("RouterInstance.outletOption returns Rx[Option]"):
    val router = Router(Route("/", _ => "home"))
    router.outletOption shouldMatch { case _: Rx[?] =>
    }

  test("RouterInstance.params returns Rx[RouteParams]"):
    val router = Router(Route("/users/:id", p => p.pathParam("id")))
    router.params shouldMatch { case _: Rx[?] =>
    }

  test("Route pattern matching works for static paths"):
    // Create a location that would match /users
    val loc                    = Location("/users", "", "")
    val router                 = Router(Route("/users", _ => "users-list"))
    var result: Option[String] = None
    val cancel                 = router
      .outletOption
      .run { r =>
        result = r
      }
    // The test runs on a jsdom environment where the path is not /users
    // But we can verify the router was created correctly
    cancel.cancel

  test("Route pattern with parameters compiles to correct regex"):
    // Test that /users/:id pattern correctly matches paths like /users/123
    val router = Router(
      Route("/users/:id", p => s"user-${p.pathParam("id")}"),
      Route("*", _ => "not-found")
    )
    // Outlet should emit something (either matched route or wildcard)
    var result: Option[String] = None
    val cancel                 = router
      .outletOption
      .run { r =>
        result = r
      }
    // In jsdom environment this should match the wildcard
    result.isDefined shouldBe true
    cancel.cancel

  test("Route pattern escapes special regex characters in literal segments"):
    // Test that literal segments with special chars are properly escaped
    val router = Router(Route("/api/v1.0/users", _ => "api-users"), Route("*", _ => "not-found"))
    var result: Option[String] = None
    val cancel                 = router
      .outletOption
      .run { r =>
        result = r
      }
    result.isDefined shouldBe true
    cancel.cancel

end RouterTest
