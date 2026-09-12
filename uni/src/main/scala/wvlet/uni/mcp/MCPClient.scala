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

import wvlet.uni.http.{Http, HttpHeader, HttpSyncClient, Request, Response}
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{
  JSONArray,
  JSONBoolean,
  JSONLong,
  JSONNull,
  JSONObject,
  JSONString,
  JSONValue
}
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx

import java.util.concurrent.atomic.AtomicLong

/**
  * A content block returned by `tools/call` (MCP spec: `text` / `image`).
  */
enum MCPContent:
  /** Text content: plain tool output */
  case Text(text: String)

  /** Image content: base64-encoded binary data */
  case Image(data: String, mimeType: String)

/**
  * Metadata of a tool exposed by an MCP server (from `tools/list`).
  */
case class MCPToolInfo(name: String, description: Option[String], inputSchema: JSONObject)

/**
  * Result of a `tools/call`. `isError = true` is a tool execution failure reported as data, not
  * thrown (mirrors the server: protocol errors are exceptions, tool failures are results).
  */
case class MCPToolResult(
    content: Seq[MCPContent],
    isError: Boolean = false,
    structuredContent: Option[JSONObject] = None
)

/**
  * Information returned by the `initialize` handshake.
  */
case class MCPServerInfo(
    name: String,
    version: String,
    protocolVersion: String,
    capabilities: Seq[String]
)

/**
  * A JSON-RPC error reported by the MCP server using a standard JSON-RPC error code.
  */
case class MCPClientException(code: Int, message: String) extends Exception(message)

/**
  * An MCP (Model Context Protocol) client over the Streamable HTTP transport.
  *
  * {{{
  * val client = MCPClient.connect("http://localhost:8080/mcp").flatMap { client =>
  *   client.listTools().map { tools =>
  *     // discover and call tools
  *     ???
  *   }
  * }
  * }}}
  *
  * `connect` performs the MCP handshake (initialize + notifications/initialized); every call
  * returns a [[wvlet.uni.rx.Rx]] that performs a blocking HTTP POST when run (the sync channel
  * works on all platforms and matches the blocking stdio server). The client speaks the tools-only
  * scope of MCP (matching [[MCPServer]]): initialize, tools/list, tools/call, and ping. It is
  * transport-agnostic apart from Streamable HTTP; a stdio transport can be added later without
  * touching the message layer.
  *
  * `Mcp-Session-Id` returned by the server on initialize is captured and echoed on subsequent
  * requests (session-less servers such as [[MCPServer]] do not issue one).
  */
class MCPClient private[mcp] (httpClient: HttpSyncClient, serverUri: String) extends LogSupport:

  private val nextId = new AtomicLong(1L)
  @volatile
  private var sessionId: Option[String] = None

  private def sendMessage(message: String): Response =
    val request = Request
      .post(serverUri)
      .addHeader(HttpHeader.ContentType, "application/json")
      .addHeader(HttpHeader.Accept, MCPClient.AcceptHeader)
      .addHeader(MCPClient.ProtocolVersionHeader, MCPClient.ProtocolVersion)
      .withJsonContent(message)
    debug(s"Sending MCP request to ${serverUri}: ${message}")
    httpClient.send(sessionId.fold(request)(id => request.addHeader(MCPClient.SessionIdHeader, id)))

  /**
    * Parse a JSON-RPC response. HTTP 202 (notifications) yields None; JSON-RPC errors and non-2xx
    * statuses throw [[MCPClientException]].
    */
  private def parseResult(response: Response): Option[JSONValue] =
    if response.status.code == 202 then
      None
    else if !response.status.isSuccessful then
      throw MCPClientException(
        response.status.code,
        s"MCP server returned HTTP ${response.status.code}: ${response
            .contentAsString
            .getOrElse("")}"
      )
    else
      try
        val rpc = JsonRpc.parseResponse(response.contentAsString.getOrElse(""))
        rpc.error match
          case Some(e) =>
            throw MCPClientException(e.code, e.message)
          case None =>
            rpc.result
      catch
        case e: JsonRpc.JsonRpcParseException =>
          throw MCPClientException(e.code, e.message)

  /**
    * Send a JSON-RPC request (with id) and return its result. Notifications (HTTP 202) yield None.
    */
  private def call(method: String, params: Option[JSONObject]): Rx[Option[JSONValue]] =
    val id = JSONLong(nextId.getAndIncrement())
    Rx.single(parseResult(sendMessage(JsonRpc.request(Some(id), method, params))))

  /**
    * Send a JSON-RPC notification (no id). The server answers 202 with no body.
    */
  private def notify(method: String, params: Option[JSONObject]): Rx[Unit] = Rx.single {
    val response = sendMessage(JsonRpc.request(None, method, params))
    if !response.status.isSuccessful then
      throw MCPClientException(
        response.status.code,
        s"MCP server returned HTTP ${response.status.code}: ${response
            .contentAsString
            .getOrElse("")}"
      )
  }

  /**
    * Perform the `initialize` handshake and return the server info. Captures `Mcp-Session-Id` if
    * the server issues one.
    */
  def initialize(protocolVersion: String = MCPClient.ProtocolVersion): Rx[MCPServerInfo] =
    val params = JSONObject(
      Seq(
        "protocolVersion" -> JSONString(protocolVersion),
        "capabilities"    -> JSONObject(Seq.empty),
        "clientInfo"      ->
          JSONObject(
            Seq(
              "name"    -> JSONString("uni-mcp-client"),
              "version" -> JSONString(MCPClient.ClientVersion)
            )
          )
      )
    )
    Rx.single {
      val response = sendMessage(
        JsonRpc.request(Some(JSONLong(nextId.getAndIncrement())), "initialize", Some(params))
      )
      response.header(MCPClient.SessionIdHeader).foreach(id => sessionId = Some(id))
      parseServerInfo(asObject(parseResult(response).getOrElse(JSONNull())))
    }

  /**
    * Send `notifications/initialized` after a successful initialize (part of the MCP handshake).
    */
  def notifyInitialized(): Rx[Unit] = notify("notifications/initialized", None)

  /**
    * Keepalive: check that the server is still reachable.
    */
  def ping(): Rx[Unit] = call("ping", None).map(_ => ())

  /**
    * List all tools exposed by the server.
    */
  def listTools(): Rx[Seq[MCPToolInfo]] = call("tools/list", None).map {
    case Some(result) =>
      asObject(result).get("tools") match
        case Some(JSONArray(tools)) =>
          tools
            .toSeq
            .collect { case o: JSONObject =>
              parseToolInfo(o)
            }
        case _ =>
          throw MCPClientException(
            JsonRpc.InternalError,
            s"tools/list returned an unexpected response: ${result.toJSON}"
          )
    case None =>
      throw MCPClientException(JsonRpc.InternalError, "tools/list returned no result")
  }

  /**
    * Call a tool with the given arguments (passed through as-is). Tool execution failures are
    * returned as `MCPToolResult(isError = true)`; JSON-RPC and transport errors are thrown as
    * [[MCPClientException]].
    */
  def callTool(name: String, arguments: JSONObject): Rx[MCPToolResult] = call(
    "tools/call",
    Some(JSONObject(Seq("name" -> JSONString(name), "arguments" -> arguments)))
  ).map {
    case Some(result) =>
      parseToolCallResult(asObject(result))
    case None =>
      throw MCPClientException(JsonRpc.InternalError, "tools/call returned no result")
  }

  /**
    * Close the underlying HTTP client.
    */
  def close(): Unit = httpClient.close()

  private def asObject(v: JSONValue): JSONObject =
    v match
      case o: JSONObject =>
        o
      case _ =>
        throw MCPClientException(JsonRpc.InternalError, s"Expected a JSON object, got: ${v.toJSON}")

  private def parseToolInfo(obj: JSONObject): MCPToolInfo = MCPToolInfo(
    name = obj
      .get("name")
      .collect { case JSONString(v) =>
        v
      }
      .getOrElse(""),
    description = obj
      .get("description")
      .collect { case JSONString(v) =>
        v
      },
    inputSchema = obj
      .get("inputSchema")
      .collect { case o: JSONObject =>
        o
      }
      .getOrElse(JSONObject(Seq.empty))
  )

  private def parseToolCallResult(result: JSONObject): MCPToolResult = MCPToolResult(
    content = result
      .get("content")
      .collect { case JSONArray(items) =>
        items.toSeq.flatMap(parseContent)
      }
      .getOrElse(Seq.empty),
    isError = result
      .get("isError")
      .collect { case JSONBoolean(v) =>
        v
      }
      .getOrElse(false),
    structuredContent = result
      .get("structuredContent")
      .collect { case o: JSONObject =>
        o
      }
  )

  private def parseContent(v: JSONValue): Option[MCPContent] =
    v match
      case o: JSONObject =>
        o.get("type") match
          case Some(JSONString("text")) =>
            o.get("text")
              .collect { case JSONString(t) =>
                MCPContent.Text(t)
              }
          case Some(JSONString("image")) =>
            for
              data <- o
                .get("data")
                .collect { case JSONString(d) =>
                  d
                }
              mime <- o
                .get("mimeType")
                .collect { case JSONString(m) =>
                  m
                }
            yield MCPContent.Image(data, mime)
          case _ =>
            None
      case _ =>
        None

  private def parseServerInfo(result: JSONObject): MCPServerInfo =
    val serverInfo = result
      .get("serverInfo")
      .collect { case o: JSONObject =>
        o
      }
      .getOrElse(JSONObject(Seq.empty))
    MCPServerInfo(
      name = serverInfo
        .get("name")
        .collect { case JSONString(v) =>
          v
        }
        .getOrElse(""),
      version = serverInfo
        .get("version")
        .collect { case JSONString(v) =>
          v
        }
        .getOrElse(""),
      protocolVersion = result
        .get("protocolVersion")
        .collect { case JSONString(v) =>
          v
        }
        .getOrElse(MCPClient.ProtocolVersion),
      capabilities = result
        .get("capabilities")
        .collect { case o: JSONObject =>
          o.v.map(_._1)
        }
        .getOrElse(Seq.empty)
    )

  end parseServerInfo

end MCPClient

object MCPClient:

  /** The protocol version this client speaks (matches the server's latest supported version). */
  val ProtocolVersion: String = MCPServer.LatestProtocolVersion

  /** Version reported in the initialize `clientInfo`. */
  val ClientVersion: String = "0.1.0"

  private[mcp] val AcceptHeader: String          = "application/json, text/event-stream"
  private[mcp] val ProtocolVersionHeader: String = "MCP-Protocol-Version"
  private[mcp] val SessionIdHeader: String       = "Mcp-Session-Id"

  /**
    * Connect to an MCP server over Streamable HTTP and perform the MCP handshake (initialize +
    * notifications/initialized). Uses the default cross-platform HTTP client with retries disabled:
    * JSON-RPC messages (`tools/call` in particular) are not idempotent, so a retry could execute a
    * tool twice.
    */
  def connect(serverUri: String): Rx[MCPClient] = connect(
    Http.client.withMaxRetry(0).newSyncClient,
    serverUri
  )

  /**
    * Connect using a caller-provided HTTP sync client (custom base URI, retry, filters, ...).
    */
  def connect(httpClient: HttpSyncClient, serverUri: String): Rx[MCPClient] =
    val client = new MCPClient(httpClient, serverUri)
    client
      .initialize()
      .flatMap { _ =>
        client.notifyInitialized().map(_ => client)
      }

end MCPClient
