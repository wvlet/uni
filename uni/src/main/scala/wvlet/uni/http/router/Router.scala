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

import wvlet.uni.surface.Surface

/**
  * A router that holds a collection of routes. Each route carries the filters scoped to it (see
  * [[Route.filterSurfaces]]), so composing routers keeps every subtree's filters on its own routes.
  *
  * Routes are matched in order, so more specific routes should be registered first.
  *
  * @param routes
  *   The collection of routes
  */
class Router(val routes: Seq[Route]):

  /**
    * Create a router whose routes are all wrapped by the given filter (as the outermost filter).
    */
  def this(routes: Seq[Route], filterSurfaceOpt: Option[Surface]) = this(
    Router.addFilter(routes, filterSurfaceOpt)
  )

  /**
    * The outermost filter shared by every route, if any. Routes may carry further, narrower
    * filters, so backends must apply each matched route's [[Route.filterSurfaces]] instead.
    */
  @deprecated("Filters are scoped per route. Use Route.filterSurfaces instead", "2026.1.34")
  def filterSurfaceOpt: Option[Surface] = Router.commonFilters(routes).headOption

  /**
    * Combine this router with another router. The other router's routes are appended after this
    * router's routes. Each route keeps its own filters, so a filter attached to one side does not
    * apply to the other.
    *
    * @param other
    *   The router to combine with
    * @return
    *   A new Router with combined routes
    */
  def andThen(other: Router): Router = Router(routes ++ other.routes)

  /**
    * Returns the number of routes in this router
    */
  def size: Int = routes.size

  /**
    * Returns true if this router has no routes
    */
  def isEmpty: Boolean = routes.isEmpty

  /**
    * Returns a human-readable summary of all routes
    */
  def routeSummary: String =
    if routes.isEmpty then
      "Router (no routes)"
    else
      routes.map(_.description).mkString("Router:\n  ", "\n  ", "")

  override def toString: String = routeSummary

end Router

object Router:

  /**
    * Create a Router from a controller class at compile-time. The controller methods annotated with
    * `@Endpoint` will be extracted and registered as routes.
    *
    * Example:
    * {{{
    * class UserController:
    *   @Endpoint(HttpMethod.GET, "/users")
    *   def listUsers(): Seq[User] = ???
    *
    * val router = Router.of[UserController]
    * }}}
    */
  inline def of[T]: Router = RouterMacros.buildRouter[T]

  /**
    * Create a FilterBuilder to add a filter before routes.
    *
    * Example:
    * {{{
    * class LogFilter extends RxHttpFilter:
    *   def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    *     info(s"Request: \${request.method} \${request.uri}")
    *     next.handle(request)
    *
    * val router = Router
    *   .filter[LogFilter]
    *   .andThen(Router.of[UserController])
    * }}}
    */
  inline def filter[F]: FilterBuilder = FilterBuilder(Surface.of[F])

  /**
    * Create an empty router
    */
  def empty: Router = Router(Seq.empty)

  /**
    * Create a router with the given routes
    */
  def apply(routes: Seq[Route]): Router = new Router(routes)

  /**
    * Create a router with the given routes, all wrapped by the given filter as the outermost one
    */
  def apply(routes: Seq[Route], filterSurface: Option[Surface]): Router =
    new Router(routes, filterSurface)

  /**
    * The filters every route starts with (the longest common prefix of their filter chains). These
    * wrap the whole router, so they also apply to requests that match no route.
    */
  def commonFilters(routes: Seq[Route]): Seq[Surface] =
    if routes.isEmpty then
      Seq.empty
    else
      routes
        .map(_.filterSurfaces)
        .reduce { (a, b) =>
          a.zip(b).takeWhile((x, y) => x == y).map(_._1)
        }

  private def addFilter(routes: Seq[Route], filterSurfaceOpt: Option[Surface]): Seq[Route] =
    filterSurfaceOpt match
      case Some(surface) =>
        routes.map(r => r.copy(filterSurfaces = surface +: r.filterSurfaces))
      case None =>
        routes

  /**
    * Builder for adding filters to a router
    */
  class FilterBuilder(filterSurface: Surface):
    /**
      * Wrap every route of the router with this filter. Filters the routes already have stay inside
      * it, so `filter[A].andThen(filter[B].andThen(r))` runs A, then B, then the route.
      */
    def andThen(router: Router): Router = Router(router.routes, Some(filterSurface))

  end FilterBuilder

end Router
