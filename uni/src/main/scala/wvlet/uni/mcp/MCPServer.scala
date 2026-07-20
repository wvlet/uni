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

import wvlet.uni.http.{HttpHeader, HttpMethod, Request, Response}
import wvlet.uni.http.rpc.{RPCDispatcher, RPCException, RPCRoute, RPCRouter, RPCStatus}
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONArray, JSONBoolean, JSONObject, JSONString, JSONValue}
import wvlet.uni.log.LogSupport
import wvlet.uni.mcp.JsonRpc.JsonRpcRequest
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver

/**
  * An MCP (Model Context Protocol) tool derived from a service method.
  */
case class MCPTool(
    name: String,
    description: Option[String],
    inputSchema: JSONObject,
    private[mcp] val route: RPCRoute
)

/**
  * A cross-platform MCP server that exposes uni RPC service methods as MCP tools over the stdio
  * transport (JSON-RPC 2.0, newline-delimited).
  *
  * {{{
  * trait WeatherService:
  *   @description("Return the current temperature in Celsius for a city")
  *   def temperature(@description("City name") city: String): Double
  *
  * @main def start(): Unit =
  *   MCPServer()
  *     .withName("weather")
  *     .withVersion("0.1.0")
  *     .withTools[WeatherService](WeatherServiceImpl())
  *     .serveStdio()
  * }}}
  *
  * Every public method of the trait becomes a tool named after the method. Tool descriptions come
  * from the [[description]] annotation. Dispatch reuses the transport-neutral
  * [[wvlet.uni.http.rpc.RPCDispatcher]], so parameter decoding behaves exactly like uni RPC
  * (canonical-name matching, default values, `Option` handling, `Rx[A]` return types).
  *
  * On JVM and Scala Native, `serveStdio()` blocks until stdin reaches EOF. On Scala.js (Node.js) it
  * returns immediately and the server runs on the event loop.
  */
case class MCPServer(
    name: String = "uni-mcp",
    version: String = "0.1.0",
    routers: List[RPCRouter] = Nil,
    allowedOrigins: List[String] = Nil
) extends LogSupport:

  def withName(name: String): MCPServer       = copy(name = name)
  def withVersion(version: String): MCPServer = copy(version = version)

  /**
    * Origins (e.g. "https://app.example.com") accepted by the HTTP transport in addition to
    * localhost. See [[httpHandler]].
    */
  def withAllowedOrigins(origins: String*): MCPServer = copy(allowedOrigins = origins.toList)

  /**
    * Register every public method of service trait T as an MCP tool. Fails fast on tool-name
    * collisions, invalid tool names, and parameter/return types that cannot be encoded to JSON.
    */
  inline def withTools[T](impl: T): MCPServer = withRouter(RPCRouter.of[T](impl))

  /**
    * Register a pre-built RPC router's methods as MCP tools.
    */
  def withRouter(router: RPCRouter): MCPServer =
    val existing = routers.flatMap(_.routes).map(_.methodName).toSet
    router
      .routes
      .foreach { route =>
        MCPServer.validateRoute(route)
        if existing.contains(route.methodName) then
          throw IllegalArgumentException(
            s"Duplicate MCP tool name: '${route.methodName}'. Tool names are flat, so method " +
              "names must be unique across all registered services."
          )
      }
    copy(routers = routers :+ router)

  /**
    * All tools exposed by this server, sorted by name. Derived once per server instance.
    */
  lazy val tools: Seq[MCPTool] = routers
    .flatMap(router => router.routes.map(route => (router.instance, route)))
    .map { (instance, route) =>
      val method = route.codec.method
      MCPTool(
        name = route.methodName,
        description = description.value(method.findAnnotation("description")),
        inputSchema = JsonSchema.ofMethodArgs(method, Some(instance)),
        route = route
      )
    }
    .sortBy(_.name)

  /**
    * Handle a single incoming JSON-RPC message and return the response line to write, if any
    * (notifications produce no response). This is the transport-independent core, usable directly
    * in tests.
    */
  def handleMessage(line: String): Rx[Option[String]] =
    JsonRpc.parseRequest(line) match
      case Left((id, code, message)) =>
        Rx.single(Some(JsonRpc.errorResponse(id, code, message)))
      case Right(request) =>
        request.id match
          case None =>
            // Notifications (e.g. notifications/initialized) expect no response, even on error
            Rx.single(None)
          case Some(id) =>
            handleRequest(id, request)

  private lazy val dispatcher: RPCDispatcher         = RPCDispatcher(routers*)
  private lazy val toolsByName: Map[String, MCPTool] = tools.map(t => t.name -> t).toMap
  private val emptyObject: JSONObject                = JSONObject(Seq.empty)

  private def handleRequest(id: JSONValue, request: JsonRpcRequest): Rx[Option[String]] =
    try
      request.method match
        case "initialize" =>
          Rx.single(Some(JsonRpc.resultResponse(id, initializeResult(request.params))))
        case "ping" =>
          Rx.single(Some(JsonRpc.resultResponse(id, emptyObject)))
        case "tools/list" =>
          Rx.single(Some(JsonRpc.resultResponse(id, toolsListResult)))
        case "tools/call" =>
          handleToolCall(id, request.params)
        case other =>
          Rx.single(
            Some(JsonRpc.errorResponse(id, JsonRpc.MethodNotFound, s"Unknown method: ${other}"))
          )
    catch
      case e: Exception =>
        warn(s"Unexpected error while handling '${request.method}'", e)
        Rx.single(Some(JsonRpc.errorResponse(id, JsonRpc.InternalError, s"${e.getMessage}")))

  private def initializeResult(params: Option[JSONObject]): JSONObject =
    val requestedVersion = params
      .flatMap(_.get("protocolVersion"))
      .collect { case JSONString(v) =>
        v
      }
    val negotiated = requestedVersion
      .filter(MCPServer.SupportedProtocolVersions.contains)
      .getOrElse(MCPServer.LatestProtocolVersion)
    JSONObject(
      Seq(
        "protocolVersion" -> JSONString(negotiated),
        "capabilities"    -> JSONObject(Seq("tools" -> emptyObject)),
        "serverInfo"      ->
          JSONObject(Seq("name" -> JSONString(name), "version" -> JSONString(version)))
      )
    )

  private lazy val toolsListResult: JSONObject = JSONObject(
    Seq(
      "tools" ->
        JSONArray(
          tools
            .map { t =>
              val fields = Seq.newBuilder[(String, JSONValue)]
              fields += "name" -> JSONString(t.name)
              t.description.foreach(d => fields += "description" -> JSONString(d))
              fields += "inputSchema" -> t.inputSchema
              JSONObject(fields.result())
            }
            .toIndexedSeq
        )
    )
  )

  private def handleToolCall(id: JSONValue, params: Option[JSONObject]): Rx[Option[String]] =
    val toolName = params
      .flatMap(_.get("name"))
      .collect { case JSONString(v) =>
        v
      }
    toolName.flatMap(toolsByName.get) match
      case None =>
        Rx.single(
          Some(
            JsonRpc.errorResponse(
              id,
              JsonRpc.InvalidParams,
              s"Unknown tool: ${toolName.getOrElse("(missing 'name' parameter)")}"
            )
          )
        )
      case Some(tool) =>
        val arguments = params
          .flatMap(_.get("arguments"))
          .collect { case o: JSONObject =>
            o
          }
          .getOrElse(emptyObject)
        // Wrap the MCP arguments in the RPC envelope and reuse the shared dispatcher, exactly
        // like the Electron IPC transport does
        val request = Request(HttpMethod.POST, tool.route.path).withJsonContent(
          JSONObject(Seq("request" -> arguments)).toJSON
        )
        dispatcher
          .dispatch(request)
          .map(response => Some(toolCallResponse(id, response)))
          .recover { case e =>
            Some(JsonRpc.errorResponse(id, JsonRpc.InternalError, s"${e.getMessage}"))
          }

  end handleToolCall

  private def toolCallResponse(id: JSONValue, response: Response): String =
    val isSuccess = response
      .header(HttpHeader.XRPCStatus)
      .contains(RPCStatus.SUCCESS_S0.code.toString)
    if isSuccess then
      val body = response.contentAsString.getOrElse("null")
      // A tool returning String is JSON-encoded as "..." by the RPC layer; unwrap it so MCP
      // clients receive the plain text rather than a quoted string
      val text =
        if body.startsWith("\"") then
          try
            JSON.parseAny(body) match
              case JSONString(v) =>
                v
              case _ =>
                body
          catch
            case _: Exception =>
              body
        else
          body
      JsonRpc.resultResponse(id, toolResult(text, isError = false))
    else
      val e = RPCException.fromResponse(response)
      e.status match
        case RPCStatus.INVALID_REQUEST_U1 | RPCStatus.INVALID_ARGUMENT_U2 =>
          // Malformed arguments are protocol-level errors per the MCP spec
          JsonRpc.errorResponse(id, JsonRpc.InvalidParams, e.message)
        case _ =>
          // Tool execution failures are reported as results with isError = true
          JsonRpc.resultResponse(id, toolResult(e.message, isError = true))

  end toolCallResponse

  private def toolResult(text: String, isError: Boolean): JSONObject = JSONObject(
    Seq(
      "content" ->
        JSONArray(
          IndexedSeq(JSONObject(Seq("type" -> JSONString("text"), "text" -> JSONString(text))))
        ),
      "isError" -> JSONBoolean(isError)
    )
  )

  /**
    * Serve MCP over stdio: read newline-delimited JSON-RPC messages from stdin and write responses
    * to stdout. Logs go to stderr so they never corrupt the protocol stream. Blocks until stdin EOF
    * on JVM/Native; returns immediately (event-loop driven) on Scala.js.
    */
  def serveStdio(): Unit =
    // Force tool derivation before serving so invalid setups fail on startup
    val toolCount = toolsByName.size
    debug(s"Serving ${toolCount} MCP tool(s) over stdio")
    StdioTransport.serve(handleMessage)

  /**
    * MCP Streamable HTTP transport as an [[wvlet.uni.http.RxHttpHandler]]: mount it on any uni HTTP
    * server, e.g. `NettyServer.withPort(8080).withRxHandler(mcp.httpHandler).start()` (JVM), or the
    * same with `NodeServer` (Scala.js) / `NativeServer` (Scala Native). Each POSTed JSON-RPC
    * message is answered with `application/json` (202 for notifications); when an `Origin` header
    * is present, only localhost origins plus [[allowedOrigins]] are accepted.
    */
  def httpHandler: wvlet.uni.http.RxHttpHandler =
    // Force tool derivation so invalid setups fail when the handler is created, not per request
    val toolCount = toolsByName.size
    debug(s"Serving ${toolCount} MCP tool(s) over HTTP")
    MCPHttpHandler(this)

end MCPServer

object MCPServer:
  val LatestProtocolVersion: String          = "2025-06-18"
  val SupportedProtocolVersions: Seq[String] = Seq("2025-06-18", "2025-03-26")

  // MCP tool names must be usable by any client; keep to a conservative charset
  private val ToolNamePattern = "[a-zA-Z0-9_-]{1,128}".r

  private def validateRoute(route: RPCRoute): Unit =
    val method = route.codec.method
    if !ToolNamePattern.matches(route.methodName) then
      throw IllegalArgumentException(
        s"Invalid MCP tool name: '${route.methodName}'. Tool names must match [a-zA-Z0-9_-]{1,128}"
      )
    method
      .args
      .foreach { p =>
        if Weaver.fromSurfaceOpt(p.surface).isEmpty then
          throw IllegalArgumentException(
            s"Parameter '${p.name}' of tool '${route.methodName}' has unsupported type: " +
              s"${p.surface.fullName}"
          )
      }
    val returnType =
      val r = method.returnType
      if classOf[Rx[?]].isAssignableFrom(r.rawType) && r.typeArgs.nonEmpty then
        r.typeArgs.head
      else
        r
    if returnType.rawType != classOf[Unit] && Weaver.fromSurfaceOpt(returnType).isEmpty then
      throw IllegalArgumentException(
        s"Return type of tool '${route.methodName}' is unsupported: ${returnType.fullName}"
      )

end MCPServer
