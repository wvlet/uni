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
  * A router that holds a collection of routes and an optional filter factory.
  *
  * Routes are matched in order, so more specific routes should be registered first.
  *
  * @param routes
  *   The collection of routes
  * @param filterSurfaceOpt
  *   Optional Surface of the filter to apply to all routes
  */
class Router(val routes: Seq[Route], val filterSurfaceOpt: Option[Surface]):

  /**
    * Combine this router with another router. The other router's routes are appended after this
    * router's routes.
    *
    * @param other
    *   The router to combine with
    * @return
    *   A new Router with combined routes
    */
  def andThen(other: Router): Router = Router(
    routes ++ other.routes,
    filterSurfaceOpt.orElse(other.filterSurfaceOpt)
  )

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
  def empty: Router = Router(Seq.empty, None)

  /**
    * Create a router with the given routes
    */
  def apply(routes: Seq[Route], filterSurface: Option[Surface]): Router =
    new Router(routes, filterSurface)

  /**
    * Builder for adding filters to a router
    */
  class FilterBuilder(filterSurface: Surface):
    /**
      * Combine this filter with a router
      */
    def andThen(router: Router): Router = Router(router.routes, Some(filterSurface))

  end FilterBuilder

end Router
