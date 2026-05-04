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
package wvlet.uni.http.netty

import wvlet.uni.http.{Request, Response}
import wvlet.uni.http.router.*
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.surface.{OptionSurface, Surface}
import wvlet.uni.weaver.Weaver

import java.util.IdentityHashMap

/**
  * An RxHttpHandler implementation that dispatches requests to controller methods based on route
  * matching.
  *
  * @param router
  *   The router containing route definitions
  * @param controllerProvider
  *   Provider for obtaining controller instances
  */
class RouterHandler(router: Router, controllerProvider: ControllerProvider)
    extends RxHttpHandler
    with LogSupport:

  private val matcher = RouteMatcher(router.routes)
  private val mapper  = HttpRequestMapper()

  // Pre-compute Weavers for each route's return type so case-class results are encoded as JSON.
  // The weaver is derived against the inner element surface (Rx[A] / Option[A] are peeled) to
  // match how ResponseConverter unwraps these wrappers before encoding the value. We skip
  // building a weaver for `Response`/`Rx[Response]` returns since ResponseConverter
  // short-circuits on those before consulting any weaver.
  // IdentityHashMap keys on reference equality — every Route returned by RouteMatcher is a
  // direct reference into router.routes, so this avoids walking case-class equals/hashCode on
  // every request.
  private val returnWeavers: IdentityHashMap[Route, Weaver[?]] =
    val m = new IdentityHashMap[Route, Weaver[?]](router.routes.size * 2)
    router
      .routes
      .foreach { r =>
        val element = RouterHandler.elementSurface(r.methodSurface.returnType)
        if element.rawType != classOf[Response] then
          m.put(r, Weaver.fromSurface(element))
      }
    m

  // Lazily initialized filter instance (thread-safe)
  private lazy val filterInstance: Option[RxHttpFilter] = router
    .filterSurfaceOpt
    .map { surface =>
      controllerProvider.getFilter(surface).asInstanceOf[RxHttpFilter]
    }

  override def handle(request: Request): Rx[Response] =
    filterInstance match
      case Some(filter) =>
        // Apply filter before handling
        filter.apply(request, RxHttpHandler(handleRequest))
      case None =>
        handleRequest(request)

  private def handleRequest(request: Request): Rx[Response] =
    matcher.findRoute(request) match
      case Some(routeMatch) =>
        try
          val controller = controllerProvider.get(routeMatch.route.controllerSurface)
          val args       = mapper.bindParameters(
            request,
            routeMatch.route.methodSurface,
            routeMatch.pathParams,
            Some(controller)
          )
          val result = routeMatch.route.methodSurface.call(controller, args*)
          val weaver = returnWeavers.get(routeMatch.route)
          if weaver == null then
            ResponseConverter.toResponse(result)
          else
            ResponseConverter.toResponse(result, weaver)
        catch
          case e: HttpRequestMappingException =>
            debug(s"Parameter mapping error: ${e.getMessage}")
            Rx.single(Response.badRequest(e.getMessage))
          case e: Exception =>
            warn(s"Error handling request: ${e.getMessage}", e)
            Rx.single(Response.internalServerError(e.getMessage))
      case None =>
        debug(s"No route found for ${request.method} ${request.path}")
        Rx.single(Response.notFound(s"Not found: ${request.method} ${request.path}"))

end RouterHandler

object RouterHandler:

  /**
    * Peel wrapper types from a return-type surface to derive the surface of the value that the
    * Weaver actually has to encode.
    *
    * Peeling rules mirror what [[ResponseConverter]] does at runtime: it unwraps `Rx[_]` only at
    * the top level (`toResponse`'s `case rx: Rx[?] => rx.map(...)` branch), then unwraps `Option`
    * recursively inside the per-value path. We follow the same shape — peel at most one `Rx`, then
    * peel any number of nested `Option`s — so the weaver lines up with the value the converter
    * eventually hands it. Nested `Rx[Rx[_]]` is not supported by the converter and is left as-is
    * here too.
    */
  private def elementSurface(surface: Surface): Surface =
    val afterRx =
      surface match
        case s if classOf[Rx[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
          s.typeArgs.head
        case other =>
          other
    unwrapOption(afterRx)

  @scala.annotation.tailrec
  private def unwrapOption(surface: Surface): Surface =
    surface match
      case opt: OptionSurface =>
        unwrapOption(opt.elementSurface)
      case other =>
        other

  /**
    * Create a RouterHandler with a SimpleControllerProvider.
    */
  def apply(router: Router): RouterHandler = RouterHandler(router, SimpleControllerProvider())

  /**
    * Create a RouterHandler with a custom controller provider.
    */
  def apply(router: Router, controllerProvider: ControllerProvider): RouterHandler =
    new RouterHandler(router, controllerProvider)

  /**
    * Create a RouterHandler with pre-registered controller instances.
    */
  def withControllers(router: Router, controllers: Any*): RouterHandler = RouterHandler(
    router,
    MapControllerProvider(controllers*)
  )

end RouterHandler
