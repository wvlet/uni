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

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js

/**
  * URL location data representing the current browser location.
  *
  * @param pathname
  *   The path portion of the URL (e.g., "/users/123")
  * @param search
  *   The query string including "?" (e.g., "?sort=name")
  * @param hash
  *   The hash portion including "#" (e.g., "#section")
  */
case class Location(pathname: String, search: String, hash: String):
  /**
    * Parse query string into key-value pairs.
    */
  def queryParams: Map[String, String] =
    if search.isEmpty || search == "?" then
      Map.empty
    else
      val queryString =
        if search.startsWith("?") then
          search.substring(1)
        else
          search
      queryString
        .split("&")
        .flatMap { pair =>
          val parts = pair.split("=", 2)
          if parts.length == 2 then
            Some(decodeURIComponent(parts(0)) -> decodeURIComponent(parts(1)))
          else if parts.length == 1 && parts(0).nonEmpty then
            Some(decodeURIComponent(parts(0)) -> "")
          else
            None
        }
        .toMap

  /**
    * Get hash value without "#" prefix.
    */
  def hashValue: Option[String] =
    if hash.isEmpty || hash == "#" then
      None
    else
      Some(
        if hash.startsWith("#") then
          hash.substring(1)
        else
          hash
      )

  private def decodeURIComponent(s: String): String =
    try
      js.URIUtils.decodeURIComponent(s)
    catch
      case _: Exception =>
        s

end Location

/**
  * Parsed route parameters extracted from URL matching.
  *
  * @param path
  *   Named path parameters (e.g., ":id" -> "123")
  * @param query
  *   Query string parameters (e.g., "sort" -> "name")
  * @param hash
  *   Hash value without "#" prefix
  */
case class RouteParams(
    path: Map[String, String] = Map.empty,
    query: Map[String, String] = Map.empty,
    hash: Option[String] = None
):
  /**
    * Get a path parameter by name, throwing if not found.
    */
  def pathParam(name: String): String = path.getOrElse(
    name,
    throw new NoSuchElementException(s"Path parameter '${name}' not found")
  )

  /**
    * Get a path parameter by name as Option.
    */
  def pathParamOption(name: String): Option[String] = path.get(name)

  /**
    * Get a query parameter by name as Option.
    */
  def queryParam(name: String): Option[String] = query.get(name)

  /**
    * Get a query parameter by name with default value.
    */
  def queryParamOrElse(name: String, default: String): String = query.getOrElse(name, default)

end RouteParams

/**
  * Route definition that maps a URL pattern to a component.
  *
  * Pattern syntax:
  *   - `/users` - Exact match
  *   - `/users/:id` - Named parameter (captures "id")
  *   - `/users/:id/posts/:postId` - Multiple parameters
  *   - `*` - Wildcard (matches any path, typically used for 404)
  *
  * @param pattern
  *   URL pattern to match
  * @param render
  *   Function to render component from route params
  */
case class Route[A](pattern: String, render: RouteParams => A)

object Route:
  /**
    * Create a route with no parameters.
    */
  def apply[A](pattern: String, component: => A): Route[A] = Route(pattern, _ => component)

/**
  * Client-side router using the History API.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // Define routes
  *   val router = Router(
  *     Route("/", _ => HomePage()),
  *     Route("/users", _ => UserListPage()),
  *     Route("/users/:id", p => UserPage(p.pathParam("id"))),
  *     Route("*", _ => NotFoundPage())
  *   )
  *
  *   // Main app
  *   def App() = div(
  *     nav(
  *       router.link("/", "Home"),
  *       router.link("/users", "Users")
  *     ),
  *     main(router.outlet)
  *   )
  *
  *   // Programmatic navigation
  *   Router.push("/users/123")
  *   Router.back()
  * }}}
  */
object Router:
  private lazy val locationVar: RxVar[Location] =
    val initial = Location(
      dom.window.location.pathname,
      dom.window.location.search,
      dom.window.location.hash
    )
    val rxVar = Rx.variable(initial)

    // Listen for browser navigation (back/forward buttons)
    val handler: js.Function1[dom.PopStateEvent, Unit] =
      _ =>
        rxVar :=
          Location(
            dom.window.location.pathname,
            dom.window.location.search,
            dom.window.location.hash
          )
    dom.window.addEventListener("popstate", handler)

    rxVar

  /**
    * Reactive stream of the current location.
    */
  def location: Rx[Location] = locationVar

  /**
    * Reactive stream of the current pathname.
    */
  def pathname: Rx[String] = locationVar.map(_.pathname)

  /**
    * Reactive stream of the current query string.
    */
  def search: Rx[String] = locationVar.map(_.search)

  /**
    * Reactive stream of the current hash.
    */
  def hash: Rx[String] = locationVar.map(_.hash)

  /**
    * Get the current location synchronously.
    */
  def currentLocation: Location = locationVar.get

  /**
    * Navigate to a new path, adding an entry to the history.
    */
  def push(path: String): Unit =
    dom.window.history.pushState(null, "", path)
    updateLocation()

  /**
    * Navigate to a new path, replacing the current history entry.
    */
  def replace(path: String): Unit =
    dom.window.history.replaceState(null, "", path)
    updateLocation()

  /**
    * Go back in history.
    */
  def back(): Unit = dom.window.history.back()

  /**
    * Go forward in history.
    */
  def forward(): Unit = dom.window.history.forward()

  /**
    * Go to a specific point in history.
    */
  def go(delta: Int): Unit = dom.window.history.go(delta)

  private def updateLocation(): Unit =
    locationVar :=
      Location(dom.window.location.pathname, dom.window.location.search, dom.window.location.hash)

  /**
    * Create a router instance with the given routes.
    */
  def apply[A](routes: Route[A]*): RouterInstance[A] = RouterInstance(routes)

end Router

/**
  * Router instance that matches routes and provides navigation helpers.
  */
class RouterInstance[A](routes: Seq[Route[A]]):
  private val compiledRoutes: Seq[CompiledRoute[A]] = routes.map(CompiledRoute.compile)

  /**
    * Current matched route result as reactive stream. Returns None if no route matches.
    */
  def outletOption: Rx[Option[A]] = Router
    .location
    .map { loc =>
      matchRoute(loc).map { case (route, params) =>
        route.render(params)
      }
    }

  /**
    * Current matched route result as reactive stream. Throws if no route matches.
    */
  def outlet: Rx[A] = Router
    .location
    .map { loc =>
      matchRoute(loc) match
        case Some((route, params)) =>
          route.render(params)
        case None =>
          throw new NoSuchElementException(s"No route matches path: ${loc.pathname}")
    }

  /**
    * Current route params as reactive stream.
    */
  def params: Rx[RouteParams] = Router
    .location
    .map { loc =>
      matchRoute(loc).map(_._2).getOrElse(RouteParams())
    }

  /**
    * Create a link element that navigates without page reload.
    */
  def link(path: String, children: DomNode*): RxElement = RouterLink(path, isActive(path), children)

  /**
    * Check if a path matches the current location (prefix match).
    */
  def isActive(path: String): Rx[Boolean] = Router
    .pathname
    .map { current =>
      if path == "/" then
        current == "/"
      else
        current == path || current.startsWith(s"${path}/")
    }

  /**
    * Check if a path exactly matches the current location.
    */
  def isActiveExact(path: String): Rx[Boolean] = Router.pathname.map(_ == path)

  private def matchRoute(location: Location): Option[(Route[A], RouteParams)] = compiledRoutes
    .iterator
    .flatMap(_.matchPath(location))
    .nextOption()

end RouterInstance

/**
  * Internal: Compiled route with regex pattern for efficient matching.
  */
private case class CompiledRoute[A](
    route: Route[A],
    regex: scala.util.matching.Regex,
    paramNames: Seq[String],
    isWildcard: Boolean
):
  def matchPath(location: Location): Option[(Route[A], RouteParams)] =
    if isWildcard then
      Some(
        route ->
          RouteParams(path = Map.empty, query = location.queryParams, hash = location.hashValue)
      )
    else
      regex
        .findFirstMatchIn(location.pathname)
        .map { m =>
          val pathParams =
            paramNames
              .zipWithIndex
              .map { case (name, i) =>
                name -> m.group(i + 1)
              }
              .toMap
          route ->
            RouteParams(path = pathParams, query = location.queryParams, hash = location.hashValue)
        }

end CompiledRoute

private object CompiledRoute:
  // Pattern for named parameters like :id, :userId
  private val paramPattern = ":([a-zA-Z][a-zA-Z0-9_]*)".r

  def compile[A](route: Route[A]): CompiledRoute[A] =
    if route.pattern == "*" then
      // Wildcard matches everything
      CompiledRoute(route, ".*".r, Seq.empty, isWildcard = true)
    else
      val paramNames = paramPattern.findAllMatchIn(route.pattern).map(_.group(1)).toSeq
      // Build regex by escaping literal segments and inserting capture groups for params
      val segments = route
        .pattern
        .split("/")
        .map { segment =>
          if segment.startsWith(":") then
            "([^/]+)" // Capture group for parameter
          else if segment == "*" then
            ".*" // Wildcard segment
          else
            scala.util.matching.Regex.quote(segment) // Escape literal segment
        }
      val regexPattern = segments.mkString("/")
      CompiledRoute(route, s"^${regexPattern}$$".r, paramNames, isWildcard = false)

end CompiledRoute

/**
  * Internal: Router link element that prevents default navigation.
  */
private case class RouterLink(path: String, active: Rx[Boolean], children: Seq[DomNode])
    extends RxElement:
  override def render: RxElement =
    import HtmlTags.{tag, attr, handler}
    val onclick                    = handler[dom.MouseEvent]("onclick")
    val allModifiers: Seq[DomNode] =
      Seq(
        attr("href")(path),
        onclick { (e: dom.MouseEvent) =>
          // Allow modified clicks to open in new tab
          if !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey then
            e.preventDefault()
            Router.push(path)
        },
        ClassToggle("active").when(active)
      ) ++ children
    tag("a")(allModifiers*)

end RouterLink
