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
    * Parse one JSON-RPC message. Returns Left((id, code, message)) when the message cannot be
    * dispatched; the caller must still swallow errors for notifications (id = None only when the
    * message shape prevented reading an id).
    */
  def parseRequest(line: String): Either[(JSONValue, Int, String), JsonRpcRequest] =
    val parsed =
      try
        Right(JSON.parse(line))
      catch
        case e: Exception =>
          Left((JSONNull(), ParseError, s"Invalid JSON: ${e.getMessage}"))
    parsed.flatMap {
      case obj: JSONObject =>
        val id = obj.get("id")
        obj.get("method") match
          case Some(JSONString(method)) =>
            obj.get("params") match
              case None =>
                Right(JsonRpcRequest(id, method, None))
              case Some(params: JSONObject) =>
                Right(JsonRpcRequest(id, method, Some(params)))
              case Some(_) =>
                Left((id.getOrElse(JSONNull()), InvalidRequest, "'params' must be an object"))
          case _ =>
            Left((id.getOrElse(JSONNull()), InvalidRequest, "Missing 'method' field"))
      case _: JSONArray =>
        // JSON-RPC batching was removed from MCP in the 2025-06-18 protocol revision
        Left((JSONNull(), InvalidRequest, "Batch requests are not supported"))
      case _ =>
        Left((JSONNull(), InvalidRequest, "Request must be a JSON object"))
    }

  end parseRequest

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
