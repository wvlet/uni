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

import wvlet.uni.http.{Request, Response, RxHttpFilter, RxHttpHandler}
import wvlet.uni.http.router.*
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx
import wvlet.uni.surface.{OptionSurface, Surface}
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig

import java.util.IdentityHashMap
import scala.util.control.NonFatal

/**
  * An RxHttpHandler implementation that dispatches requests to controller methods based on route
  * matching.
  *
  * Each matched route runs through its own filter chain ([[Route.filterSurfaces]]), outermost
  * first. Filters shared by every route run before route matching, so they also apply to unmatched
  * requests; narrower filters run only for the routes they were attached to.
  *
  * A controller or filter exception is returned as a failed `Rx` (as an exception inside a returned
  * `Rx` already is), so an enclosing [[RxHttpFilter]] can `recover` from the original exception.
  * When no filter recovers, the server renders it as a generic 500 response.
  *
  * @param router
  *   The router containing route definitions
  * @param controllerProvider
  *   Provider for obtaining controller instances
  * @param detailedErrors
  *   Unused: 500 responses are rendered by the server
  * @param weaverConfig
  *   WeaverConfig used when encoding controller return values as JSON (e.g.,
  *   `WeaverConfig().withOmitNoneFields(true)` to omit None-valued Option fields)
  */
class RouterHandler(
    router: Router,
    controllerProvider: ControllerProvider,
    detailedErrors: Boolean = false,
    weaverConfig: WeaverConfig = WeaverConfig()
) extends RxHttpHandler
    with LogSupport:

  @deprecated(
    "Controller exceptions now propagate to the server, which renders 500 responses. Use NettyServerConfig.withDetailedErrors instead",
    "2026.1.29"
  )
  def withDetailedErrors(enabled: Boolean): RouterHandler =
    new RouterHandler(router, controllerProvider, enabled, weaverConfig)

  /**
    * Set the WeaverConfig used when encoding controller return values as JSON.
    */
  def withWeaverConfig(config: WeaverConfig): RouterHandler =
    new RouterHandler(router, controllerProvider, detailedErrors, config)

  private val matcher = RouteMatcher(router.routes)
  private val mapper  = HttpRequestMapper()

  // Pre-compute Weavers for each route's return type so case-class results are encoded as JSON.
  // The weaver is derived against the inner element surface (Rx[A] / Option[A] are peeled) to
  // match how ResponseConverter unwraps these wrappers before encoding the value. We use
  // `fromSurfaceOpt` so routes whose return type isn't covered by a built-in factory (e.g.
  // `Either`, JVM-only `LocalDate`, `Path`) fall through to ResponseConverter's no-weaver path
  // instead of getting silently encoded as the empty-object fallback. We also skip:
  //   - `Response` / `Rx[Response]` — ResponseConverter short-circuits before any weaver call.
  //   - Primitive surfaces (Int/Long/Boolean/Double/etc.) — the no-weaver path produces a
  //     toString-quoted JSON string (`"42"`) for these, while the weaver path would emit a
  //     bare JSON scalar (`42`). Skipping preserves wire format for existing scalar-returning
  //     endpoints; case classes / collections / ULID / Instant / UUID still flow through the
  //     weaver and benefit from proper JSON encoding.
  // IdentityHashMap keys on reference equality — every Route returned by RouteMatcher is a
  // direct reference into router.routes, so this avoids walking case-class equals/hashCode on
  // every request.
  private val returnWeavers: IdentityHashMap[Route, Weaver[?]] =
    val m = new IdentityHashMap[Route, Weaver[?]](router.routes.size * 2)
    router
      .routes
      .foreach { r =>
        val element = RouterHandler.elementSurface(r.methodSurface.returnType)
        if !classOf[Response].isAssignableFrom(element.rawType) && !element.isPrimitive then
          Weaver.fromSurfaceOpt(element).foreach(m.put(r, _))
      }
    m

  // Filter instances are created lazily through the ControllerProvider, one per filter Surface
  // shared by every route that uses it
  private class LazyFilter(surface: Surface):
    lazy val instance: RxHttpFilter = controllerProvider
      .getFilter(surface)
      .asInstanceOf[RxHttpFilter]

  private val filterCache: Map[Surface, LazyFilter] =
    router.routes.flatMap(_.filterSurfaces).distinct.map(s => s -> LazyFilter(s)).toMap

  // Filters shared by all routes wrap the whole router: they run before route matching (so they
  // may rewrite the request and also see unmatched requests), as a router-wide filter always did
  private val commonFilters: IndexedSeq[LazyFilter] =
    Router.commonFilters(router.routes).map(filterCache).toIndexedSeq

  // The remaining, narrower filters of each route run after the route is matched
  private val scopedFilters: IdentityHashMap[Route, IndexedSeq[LazyFilter]] =
    val m = new IdentityHashMap[Route, IndexedSeq[LazyFilter]](router.routes.size * 2)
    router
      .routes
      .foreach { r =>
        m.put(r, r.filterSurfaces.drop(commonFilters.size).map(filterCache).toIndexedSeq)
      }
    m

  override def handle(request: Request): Rx[Response] = runFilters(
    commonFilters,
    0,
    request,
    dispatch
  )

  /**
    * Run filters(index..) outer-to-inner, then the handler. A filter's synchronous exception is
    * returned as a failed Rx, so outer filters can recover from it.
    */
  private def runFilters(
      filters: IndexedSeq[LazyFilter],
      index: Int,
      request: Request,
      handler: Request => Rx[Response]
  ): Rx[Response] =
    if index >= filters.size then
      handler(request)
    else
      try
        filters(index)
          .instance
          .apply(request, RxHttpHandler(req => runFilters(filters, index + 1, req, handler)))
      catch
        case NonFatal(e) =>
          Rx.exception(e)

  private def dispatch(request: Request): Rx[Response] =
    matcher.findRoute(request) match
      case Some(routeMatch) =>
        runFilters(
          scopedFilters.get(routeMatch.route),
          0,
          request,
          req => handleRoute(req, routeMatch)
        )
      case None =>
        debug(s"No route found for ${request.method} ${request.path}")
        Rx.single(Response.notFound(s"Not found: ${request.method} ${request.path}"))

  private def handleRoute(request: Request, routeMatch: RouteMatch): Rx[Response] =
    try
      val controller = controllerProvider.get(routeMatch.route.controllerSurface)
      val args       = mapper.bindParameters(
        request,
        routeMatch.route.methodSurface,
        routeMatch.pathParams,
        Some(controller),
        routeMatch.route.paramWeavers
      )
      val result = routeMatch.route.methodSurface.call(controller, args*)
      val weaver = returnWeavers.get(routeMatch.route)
      if weaver == null then
        ResponseConverter.toResponse(result)
      else
        ResponseConverter.toResponse(result, weaver, weaverConfig)
    catch
      case e: HttpRequestMappingException =>
        debug(s"Parameter mapping error: ${e.getMessage}")
        Rx.single(Response(e.status).withTextContent(e.getMessage))
      case e: Exception =>
        Rx.exception(e)

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
