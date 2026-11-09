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
package wvlet.uni.http

import wvlet.uni.rx.Rx

/**
  * Which cross-origin requests a [[CorsFilter]] allows. The default allows no origin.
  *
  * @param allowsOrigin
  *   Whether a browser `Origin` may read responses. Set it with `withAllowedOrigins` or
  *   `withOriginFilter`
  * @param allowAnyOrigin
  *   Allow every origin, answered with `Access-Control-Allow-Origin: *`. Set by
  *   `withAllowedOrigins("*")`; cannot be combined with `allowCredentials`
  * @param allowedMethods
  *   Methods a preflight may request
  * @param allowedHeaders
  *   Request headers a preflight may ask for (case-insensitive). `None` allows the headers the
  *   preflight requests
  * @param exposedHeaders
  *   Response headers the page may read beyond the CORS-safelisted ones
  * @param allowCredentials
  *   Allow cookies and `Authorization` on cross-origin requests
  * @param maxAgeSeconds
  *   How long a browser may cache a preflight answer
  */
case class CorsPolicy(
    allowsOrigin: String => Boolean = _ => false,
    allowAnyOrigin: Boolean = false,
    allowedMethods: Seq[HttpMethod] = Seq(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST),
    allowedHeaders: Option[Seq[String]] = None,
    exposedHeaders: Seq[String] = Nil,
    allowCredentials: Boolean = false,
    maxAgeSeconds: Option[Int] = Some(600)
):

  /**
    * Allow exactly these origins, e.g. `https://app.example.com`. Case and a trailing slash are
    * ignored. `"*"` allows every origin.
    */
  def withAllowedOrigins(origins: String*): CorsPolicy =
    if origins.contains("*") then
      copy(allowsOrigin = _ => true, allowAnyOrigin = true)
    else
      val allowed = origins.map(HttpServerSecurity.normalizeOrigin).toSet
      copy(
        allowsOrigin = o => allowed.contains(HttpServerSecurity.normalizeOrigin(o)),
        allowAnyOrigin = false
      )

  /**
    * Allow the origins `f` accepts, e.g. by regular expression. `f` gets the raw `Origin` value;
    * anchor patterns at the end (`...\.example\.com$`) so `example.com.evil.test` does not match.
    */
  def withOriginFilter(f: String => Boolean): CorsPolicy = copy(
    allowsOrigin = f,
    allowAnyOrigin = false
  )

  def withAllowedMethods(methods: HttpMethod*): CorsPolicy = copy(allowedMethods = methods.toSeq)
  def withAllowedHeaders(names: String*): CorsPolicy = copy(allowedHeaders = Some(names.toSeq))

  /** Allow the headers each preflight requests (the default) */
  def noAllowedHeaders(): CorsPolicy = copy(allowedHeaders = None)

  def withExposedHeaders(names: String*): CorsPolicy = copy(exposedHeaders = names.toSeq)
  def withAllowCredentials(b: Boolean): CorsPolicy   = copy(allowCredentials = b)
  def withMaxAge(seconds: Int): CorsPolicy           = copy(maxAgeSeconds = Some(seconds))
  def noMaxAge(): CorsPolicy                         = copy(maxAgeSeconds = None)

  def isOriginAllowed(origin: String): Boolean = allowAnyOrigin || allowsOrigin(origin)

end CorsPolicy

/**
  * An [[RxHttpFilter]] that implements CORS for browsers calling the server from another origin.
  *
  *   - A request without `Origin` is handled as usual. Its response gets `Vary: Origin` (unless any
  *     origin is allowed), so a cache does not reuse it for a cross-origin request.
  *   - A preflight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered here,
  *     without calling the handler: `204` with the `Access-Control-Allow-*` headers when the policy
  *     allows the origin, method, and headers, `403` otherwise.
  *   - Any other request from an allowed origin gets `Access-Control-Allow-Origin` (and the
  *     credentials and exposed headers) on its response. One from a disallowed origin is handled
  *     without CORS headers, so the browser keeps the page from reading the response.
  *
  * Add it with `withFilter` on the server, inside the access log filter, so it also answers
  * preflights for paths that have no route. A handler failure is passed on unchanged, so the server
  * and the access log still report the exception, and the server's 500 gets the same CORS headers.
  */
class CorsFilter(policy: CorsPolicy) extends RxHttpFilter:
  require(
    !(policy.allowAnyOrigin && policy.allowCredentials),
    "CORS: allowing any origin together with credentials would let every site send authenticated requests and read the responses. List the allowed origins instead."
  )

  private val allowMethods = policy.allowedMethods.map(_.name).distinct.mkString(", ")
  private val allowedHeaderNames: Option[Set[String]] = policy
    .allowedHeaders
    .map(_.map(_.toLowerCase).toSet)

  override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    request.header(HttpHeader.Origin) match
      case Some(origin)
          if request.method == HttpMethod.OPTIONS &&
            request.hasHeader(HttpHeader.AccessControlRequestMethod) =>
        Rx.single(
          preflight(request, origin, request.header(HttpHeader.AccessControlRequestMethod).get)
        )
      case Some(origin) if policy.isOriginAllowed(origin) =>
        handle(request, next, addResponseHeaders(_, origin))
      case _ if policy.allowAnyOrigin =>
        next.handle(request)
      case _ =>
        // Allow-Origin depends on the request origin, so a cache must key on it even when this
        // response carries no CORS headers
        handle(request, next, addVary(_, HttpHeader.Origin))

  // Give the server's generic 500 the same headers, so a browser sees the error rather than a
  // CORS failure. The failure itself passes through for the server and the access log to report.
  private def handle(
      request: Request,
      next: RxHttpHandler,
      decorate: Response => Response
  ): Rx[Response] =
    ServerErrorResponse.decorate(request, decorate)
    next.handle(request).map(decorate)

  private def preflight(request: Request, origin: String, method: String): Response =
    val requestedHeaders = request
      .header(HttpHeader.AccessControlRequestHeaders)
      .toSeq
      .flatMap(_.split(','))
      .map(_.trim)
      .filter(_.nonEmpty)
    val headersAllowed = allowedHeaderNames.forall(allowed =>
      requestedHeaders.forall(h => allowed.contains(h.toLowerCase))
    )
    if !policy.isOriginAllowed(origin) || !policy.allowedMethods.exists(_.name == method) ||
      !headersAllowed
    then
      Response.forbidden
    else
      val allowHeaders = policy.allowedHeaders.getOrElse(requestedHeaders)
      var response     = withOriginHeaders(Response.noContent, origin)
        .setHeader(HttpHeader.AccessControlAllowMethods, allowMethods)
        .setHeader(
          HttpHeader.Vary,
          "Origin, Access-Control-Request-Method, Access-Control-Request-Headers"
        )
      if allowHeaders.nonEmpty then
        response = response.setHeader(
          HttpHeader.AccessControlAllowHeaders,
          allowHeaders.mkString(", ")
        )
      policy
        .maxAgeSeconds
        .foreach { s =>
          response = response.setHeader(HttpHeader.AccessControlMaxAge, s.toString)
        }
      response

  end preflight

  private def addResponseHeaders(response: Response, origin: String): Response =
    val withOrigin = withOriginHeaders(response, origin)
    val exposed    =
      policy
        .exposedHeaders
        .foldLeft(withOrigin)((r, h) => mergeToken(r, HttpHeader.AccessControlExposeHeaders, h))
    addVary(exposed, HttpHeader.Origin)

  private def withOriginHeaders(response: Response, origin: String): Response =
    val allowOrigin =
      if policy.allowAnyOrigin then
        "*"
      else
        origin
    val r = response.setHeader(HttpHeader.AccessControlAllowOrigin, allowOrigin)
    if policy.allowCredentials then
      r.setHeader(HttpHeader.AccessControlAllowCredentials, "true")
    else
      r

  // `Vary: *` already covers every request header
  private def addVary(response: Response, name: String): Response =
    if response.headerValues(HttpHeader.Vary).exists(_.split(',').exists(_.trim == "*")) then
      response
    else
      mergeToken(response, HttpHeader.Vary, name)

  // Add a token to a comma-separated header, keeping the values the handler set
  private def mergeToken(response: Response, header: String, token: String): Response =
    val values = response.headerValues(header).flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
    if values.exists(_.equalsIgnoreCase(token)) then
      response
    else
      response.setHeader(header, (values :+ token).mkString(", "))

end CorsFilter
