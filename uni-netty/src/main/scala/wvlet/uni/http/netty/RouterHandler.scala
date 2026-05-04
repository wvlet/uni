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
  // match how ResponseConverter unwraps these wrappers before encoding the value.
  private val returnWeavers: Map[Route, Weaver[?]] =
    router
      .routes
      .map { r =>
        r -> Weaver.fromSurface(RouterHandler.elementSurface(r.methodSurface.returnType))
      }
      .toMap

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
          ResponseConverter.toResponse(result, returnWeavers(routeMatch.route))
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
    * Peel `Rx[_]` and `Option[_]` from a return-type surface, since [[ResponseConverter]] unwraps
    * these wrappers before encoding the inner value. The weaver derived from the resulting surface
    * is what eventually handles JSON serialization for case classes, sequences, etc.
    */
  private[netty] def elementSurface(surface: Surface): Surface =
    surface match
      case opt: OptionSurface =>
        elementSurface(opt.elementSurface)
      case s if classOf[Rx[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
        elementSurface(s.typeArgs.head)
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
