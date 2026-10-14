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

/**
  * Security checks shared by every server backend (Netty, Native, Node.js) so they reject the same
  * requests and hide the same details.
  */
private[uni] object HttpServerSecurity:

  /** Body of a 500 response when detailed errors are disabled. */
  final val InternalServerErrorMessage = "Internal Server Error"

  /**
    * Build the 500 response for an unexpected server-side failure. Exception messages can carry
    * file paths, SQL, hostnames or credentials (including those of an upstream service the handler
    * called), so they reach the client only when `detailedErrors` is enabled. The caller is
    * expected to log the full exception. Handlers that want a specific status return a [[Response]]
    * instead.
    */
  def errorResponse(e: Throwable, detailedErrors: Boolean): Response =
    if detailedErrors then
      Response.internalServerError(String.valueOf(e.getMessage))
    else
      Response.internalServerError(InternalServerErrorMessage)

  /**
    * Check the `Origin` of a WebSocket upgrade to prevent cross-site WebSocket hijacking: browsers
    * attach cookies to cross-origin WebSocket handshakes, so without this check any web page could
    * open a socket as the visiting user. Returns `None` when the upgrade may proceed, or the 403
    * response to send.
    *
    *   - No `Origin` header: allowed. Non-browser clients (CLI tools, JVM/Native clients) send
    *     none.
    *   - `allowedOrigins` contains `"*"`: every origin is allowed.
    *   - Otherwise the origin must be listed in `allowedOrigins`, or be same-origin (its host:port
    *     equals the request's `Host` header).
    */
  def checkWebSocketOrigin(request: Request, allowedOrigins: Seq[String]): Option[Response] =
    request.header(HttpHeader.Origin).map(_.trim) match
      case None =>
        None
      case Some(origin) =>
        if isOriginAllowed(origin, request.header(HttpHeader.Host), allowedOrigins) then
          None
        else
          Some(Response.forbidden.withTextContent("Origin not allowed"))

  /** Host names a loopback-bound server accepts by default. See [[defaultAllowedHosts]]. */
  final val LoopbackHosts: Seq[String] = Seq("localhost", "*.localhost", "127.0.0.1", "[::1]")

  /**
    * The `Host` allowlist used when the config sets none: [[LoopbackHosts]] for a server bound to a
    * loopback address, otherwise every host (`"*"`), since a server on `0.0.0.0` cannot know the
    * names clients use to reach it.
    */
  def defaultAllowedHosts(bindHost: String): Seq[String] =
    if isLoopbackAddress(bindHost) then
      LoopbackHosts
    else
      Seq("*")

  private[http] def isLoopbackAddress(host: String): Boolean =
    val h = host.trim.toLowerCase.stripPrefix("[").stripSuffix("]")
    // IPv6 loopback in any zero-compressed form: ::1, 0:0:0:0:0:0:0:1, 0000::0001, ...
    h == "localhost" || h.startsWith("127.") || h.matches("(0{0,4}:)+0{0,3}1")

  /**
    * Check the `Host` header against an allowlist to prevent DNS rebinding: a web page can make its
    * own host name resolve to 127.0.0.1, and the browser then treats requests to a local server as
    * same-origin. The browser still sends the attacker's name in `Host`, so rejecting unknown names
    * keeps such pages out. Returns `None` when the request may proceed, or the 403 response to
    * send.
    *
    *   - No `Host` header: allowed. Browsers always send one; only HTTP/1.0 clients omit it.
    *   - `allowedHosts` contains `"*"`: every host is allowed.
    *   - Otherwise the host name (port ignored, case-insensitive) must equal an entry, or end with
    *     `.suffix` for an entry `*.suffix`.
    */
  def checkHost(request: Request, allowedHosts: Seq[String]): Option[Response] = checkHost(
    request.header(HttpHeader.Host),
    allowedHosts
  )

  /** [[checkHost]] for a backend that has the `Host` header before building a [[Request]]. */
  def checkHost(hostHeader: Option[String], allowedHosts: Seq[String]): Option[Response] =
    hostHeader match
      case Some(host) if !isHostAllowed(host, allowedHosts) =>
        Some(Response.forbidden.withTextContent("Host not allowed"))
      case _ =>
        None

  private[http] def isHostAllowed(host: String, allowedHosts: Seq[String]): Boolean =
    allowedHosts.contains("*") || {
      val name = hostName(host)
      allowedHosts.exists { entry =>
        val e = entry.trim.toLowerCase
        if e.startsWith("*.") then
          name.endsWith(e.substring(1))
        else
          name == hostName(e)
      }
    }

  /** The host name of a `Host` value, without the port, lower-cased. IPv6 keeps its brackets. */
  private def hostName(host: String): String =
    val h    = host.trim.toLowerCase
    val name =
      if h.startsWith("[") then
        h.indexOf(']') match
          case -1 =>
            h
          case end =>
            h.substring(0, end + 1)
      else
        h.lastIndexOf(':') match
          case -1 =>
            h
          case colon =>
            h.substring(0, colon)
    name.stripSuffix(".")

  private[http] def isOriginAllowed(
      origin: String,
      host: Option[String],
      allowedOrigins: Seq[String]
  ): Boolean =
    allowedOrigins.contains("*") ||
      allowedOrigins.exists(o => normalizeOrigin(o) == normalizeOrigin(origin)) ||
      host.exists(h => isSameOrigin(origin, h))

  private def normalizeOrigin(origin: String): String = origin.trim.stripSuffix("/").toLowerCase

  private def isSameOrigin(origin: String, host: String): Boolean =
    val o           = normalizeOrigin(origin)
    val schemeSplit = o.indexOf("://")
    if schemeSplit < 0 then
      false
    else
      val scheme    = o.substring(0, schemeSplit)
      val authority = o.substring(schemeSplit + 3)
      val default   =
        scheme match
          case "http" | "ws" =>
            ":80"
          case "https" | "wss" =>
            ":443"
          case _ =>
            ""
      def stripDefaultPort(a: String): String =
        if default.nonEmpty then
          a.stripSuffix(default)
        else
          a
      authority.nonEmpty && stripDefaultPort(authority) == stripDefaultPort(host.trim.toLowerCase)

  /**
    * Reject header names or values containing CR, LF or NUL. Writing such a header lets an attacker
    * who controls the value (e.g. a redirect target echoed into `Location`) inject extra headers or
    * a whole second response (HTTP response splitting).
    */
  def validateHeader(name: String, value: String): Unit =
    if name.isEmpty || containsControlChar(name) then
      throw IllegalArgumentException(s"Invalid HTTP header name: ${escape(name)}")
    if containsControlChar(value) then
      throw IllegalArgumentException(s"Invalid value for HTTP header ${name}: ${escape(value)}")

  /** Validate every header of the response. See [[validateHeader]]. */
  def validateHeaders(headers: HttpMultiMap): Unit = headers
    .entries
    .foreach { case (name, value) =>
      validateHeader(name, value)
    }

  private def containsControlChar(s: String): Boolean = s.exists(c =>
    c == '\r' || c == '\n' || c == '\u0000'
  )

  private def escape(s: String): String = s
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\u0000", "\\0")

end HttpServerSecurity
