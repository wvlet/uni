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
package wvlet.uni.mcp

import wvlet.uni.http.{HttpMethod, HttpStatus, Request, Response, RxHttpHandler}
import wvlet.uni.rx.Rx

/**
  * MCP Streamable HTTP transport: a single-endpoint [[RxHttpHandler]] that accepts POSTed JSON-RPC
  * messages and answers each with `application/json` (202 for notifications).
  *
  * This server is stateless and has no server-initiated messages (tools only), so the optional
  * parts of the transport — SSE streaming, `Mcp-Session-Id`, and the GET event stream — are not
  * offered; GET returns 405 as the spec allows.
  *
  * Security: when an `Origin` header is present, only localhost origins and those explicitly
  * allowed via `MCPServer.withAllowedOrigins` are accepted (DNS-rebinding protection required by
  * the spec). Non-browser clients that send no Origin are unaffected.
  */
private[mcp] class MCPHttpHandler(server: MCPServer) extends RxHttpHandler:

  import MCPHttpHandler.*

  override def handle(request: Request): Rx[Response] =
    if request.method != HttpMethod.POST then
      Rx.single(Response(HttpStatus.MethodNotAllowed_405))
    else if !originAllowed(request.header("Origin"), server.allowedOrigins) then
      Rx.single(Response(HttpStatus.Forbidden_403).withTextContent("Origin not allowed"))
    else
      request.header(ProtocolVersionHeader) match
        case Some(v) if !MCPServer.SupportedProtocolVersions.contains(v) =>
          Rx.single(
            Response(HttpStatus.BadRequest_400).withTextContent(
              s"Unsupported MCP protocol version: ${v}"
            )
          )
        case _ =>
          server
            .handleMessage(request.content.asString.getOrElse(""))
            .map {
              case Some(json) =>
                Response.ok.withJsonContent(json)
              case None =>
                // Notifications produce no JSON-RPC response
                Response(HttpStatus.Accepted_202)
            }

end MCPHttpHandler

private[mcp] object MCPHttpHandler:
  private val ProtocolVersionHeader = "MCP-Protocol-Version"

  // Origins that are always allowed: local browsers talking to a local server
  private val LocalHosts = Set("localhost", "127.0.0.1", "[::1]")

  /**
    * An absent Origin (non-browser client) is allowed; a present one must be a localhost origin or
    * explicitly allow-listed.
    */
  private[mcp] def originAllowed(origin: Option[String], allowed: Seq[String]): Boolean =
    origin match
      case None =>
        true
      case Some(o) =>
        allowed.contains(o) || LocalHosts.contains(hostOf(o))

  /**
    * Extract the host part of an origin like `http://localhost:3000` or `https://[::1]:8080`.
    * Anything malformed is returned whole so it cannot match an allowed host: in particular, an
    * IPv6 literal must be followed only by a port or the end of the authority — otherwise
    * `[::1].evil.com` would be validated as `[::1]` (origin-bypass, flagged by review).
    */
  private def hostOf(origin: String): String =
    val afterScheme =
      origin.indexOf("://") match
        case -1 =>
          origin
        case i =>
          origin.substring(i + 3)
    val authority =
      afterScheme.indexOf('/') match
        case -1 =>
          afterScheme
        case i =>
          afterScheme.substring(0, i)
    if authority.startsWith("[") then
      authority.indexOf(']') match
        case -1 =>
          authority
        case i =>
          if i + 1 < authority.length && authority.charAt(i + 1) != ':' then
            authority
          else
            authority.substring(0, i + 1)
    else
      authority.indexOf(':') match
        case -1 =>
          authority
        case i =>
          authority.substring(0, i)

end MCPHttpHandler
