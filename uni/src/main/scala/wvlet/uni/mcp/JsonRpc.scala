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

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONArray, JSONLong, JSONNull, JSONObject, JSONString, JSONValue}

/**
  * Minimal JSON-RPC 2.0 layer for the MCP stdio transport.
  *
  * The request `id` is kept as a raw [[JSONValue]] (string or number per spec) and echoed back
  * verbatim in responses.
  */
private[mcp] object JsonRpc:
  val Version = "2.0"

  // Standard JSON-RPC 2.0 error codes
  val ParseError     = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams  = -32602
  val InternalError  = -32603

  /**
    * A parsed JSON-RPC request or notification. `id == None` means a notification (no response may
    * be sent for it, not even an error).
    */
  case class JsonRpcRequest(id: Option[JSONValue], method: String, params: Option[JSONObject])

  /**
    * Thrown when a JSON-RPC message cannot be parsed. Carries the request id when it was readable
    * from the message (else JSONNull), together with a standard JSON-RPC error code, so the caller
    * can render an error response.
    */
  case class JsonRpcParseException(id: JSONValue, code: Int, message: String)
      extends Exception(message)

  /**
    * Parse one JSON-RPC message. Throws [[JsonRpcParseException]] when the message cannot be
    * dispatched; the caller must still swallow errors for notifications (id = None only when the
    * message shape prevented reading an id).
    */
  def parseRequest(line: String): JsonRpcRequest =
    val parsed =
      try
        JSON.parse(line)
      catch
        case e: Exception =>
          throw JsonRpcParseException(JSONNull(), ParseError, s"Invalid JSON: ${e.getMessage}")
    parsed match
      case obj: JSONObject =>
        val id = obj.get("id")
        obj.get("method") match
          case Some(JSONString(method)) =>
            obj.get("params") match
              case None =>
                JsonRpcRequest(id, method, None)
              case Some(params: JSONObject) =>
                JsonRpcRequest(id, method, Some(params))
              case Some(_) =>
                throw JsonRpcParseException(
                  id.getOrElse(JSONNull()),
                  InvalidRequest,
                  "'params' must be an object"
                )
          case _ =>
            throw JsonRpcParseException(
              id.getOrElse(JSONNull()),
              InvalidRequest,
              "Missing 'method' field"
            )
      case _: JSONArray =>
        // JSON-RPC batching was removed from MCP in the 2025-06-18 protocol revision
        throw JsonRpcParseException(JSONNull(), InvalidRequest, "Batch requests are not supported")
      case _ =>
        throw JsonRpcParseException(JSONNull(), InvalidRequest, "Request must be a JSON object")

  end parseRequest

  /**
    * Render a JSON-RPC request or notification as a single-line compact JSON string. `id == None`
    * produces a notification (no response expected).
    */
  def request(id: Option[JSONValue], method: String, params: Option[JSONObject]): String =
    val b = Seq.newBuilder[(String, JSONValue)]
    b += "jsonrpc" -> JSONString(Version)
    id.foreach(i => b += "id" -> i)
    b += "method" -> JSONString(method)
    params.foreach(p => b += "params" -> p)
    JSONObject(b.result()).toJSON

  /** A JSON-RPC error object returned by a response (`error: {code, message}`). */
  case class JsonRpcError(code: Int, message: String)

  /**
    * A parsed JSON-RPC response. Exactly one of `result`/`error` is set; `id` is the value echoed
    * by the server (a number or string per spec, kept raw).
    */
  case class JsonRpcResponse(
      id: Option[JSONValue],
      result: Option[JSONValue],
      error: Option[JsonRpcError]
  )

  /**
    * Parse a JSON-RPC response line (client side). The message must have exactly one of
    * `result`/`error`; throws [[JsonRpcParseException]] for unparseable or malformed messages.
    */
  def parseResponse(line: String): JsonRpcResponse =
    val parsed =
      try
        JSON.parse(line)
      catch
        case e: Exception =>
          throw JsonRpcParseException(JSONNull(), ParseError, s"Invalid JSON: ${e.getMessage}")
    parsed match
      case obj: JSONObject =>
        val error = obj
          .get("error")
          .collect { case e: JSONObject =>
            JsonRpcError(
              e.get("code")
                .collect { case JSONLong(c) =>
                  c.toInt
                }
                .getOrElse(InternalError),
              e.get("message")
                .collect { case JSONString(m) =>
                  m
                }
                .getOrElse("")
            )
          }
        val result = obj.get("result")
        if error.isEmpty && result.isEmpty then
          throw JsonRpcParseException(
            obj.get("id").getOrElse(JSONNull()),
            InvalidRequest,
            "Response must have 'result' or 'error'"
          )
        JsonRpcResponse(obj.get("id"), result, error)
      case _ =>
        throw JsonRpcParseException(JSONNull(), InvalidRequest, "Response must be a JSON object")

  end parseResponse

  /**
    * Render a success response as a single-line compact JSON string.
    */
  def resultResponse(id: JSONValue, result: JSONValue): String =
    JSONObject(Seq("jsonrpc" -> JSONString(Version), "id" -> id, "result" -> result)).toJSON

  /**
    * Render an error response as a single-line compact JSON string.
    */
  def errorResponse(id: JSONValue, code: Int, message: String): String =
    JSONObject(
      Seq(
        "jsonrpc" -> JSONString(Version),
        "id"      -> id,
        "error"   -> JSONObject(Seq("code" -> JSONLong(code), "message" -> JSONString(message)))
      )
    ).toJSON

end JsonRpc
