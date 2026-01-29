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
package wvlet.uni.http.rpc

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.surface.CName
import wvlet.uni.surface.MethodParameter
import wvlet.uni.surface.MethodSurface
import wvlet.uni.weaver.Weaver

/**
  * Codec for RPC method parameters and return values. Combines Weavers for each parameter to decode
  * JSON request bodies and encode response values.
  *
  * @param method
  *   The method surface containing parameter metadata
  * @param paramWeavers
  *   Weavers for each parameter, derived from Weaver.fromSurface
  * @param returnWeaver
  *   Weaver for the return type
  */
case class MethodCodec(
    method: MethodSurface,
    paramWeavers: IndexedSeq[Weaver[?]],
    returnWeaver: Weaver[?]
):
  // Pre-compute canonical name lookup for flexible param matching
  // Allows userId, user_id, user-id to all match the same parameter
  private val paramsByCanonicalName: Map[String, (Int, MethodParameter, Weaver[?])] =
    method
      .args
      .zip(paramWeavers)
      .zipWithIndex
      .map { case ((p, w), i) =>
        CName.toCanonicalName(p.name) -> (i, p, w)
      }
      .toMap

  /**
    * Decode request body to method parameters.
    *
    * Expected format:
    * {{{
    * {
    *   "request": {
    *     "param1": value1,
    *     "param2": value2
    *   }
    * }
    * }}}
    *
    * Uses CName for flexible matching - userId, user_id, user-id all match.
    */
  def decodeParams(json: String): Seq[Any] =
    if json == null || json.isBlank then
      decodeFromMap(Map.empty)
    else
      try
        JSON.parse(json) match
          case obj: JSONObject =>
            obj.get("request") match
              case Some(req: JSONObject) =>
                decodeFromMap(req.v.toMap)
              case Some(_) =>
                throw RPCStatus.INVALID_REQUEST_U1.newException("'request' field must be an object")
              case None =>
                throw RPCStatus
                  .INVALID_REQUEST_U1
                  .newException("Missing 'request' field in request body")
          case _ =>
            throw RPCStatus.INVALID_REQUEST_U1.newException("Request body must be a JSON object")
      catch
        case e: RPCException =>
          throw e
        case e: Exception =>
          throw RPCStatus.INVALID_REQUEST_U1.newException(s"Invalid JSON: ${e.getMessage}", e)

  /**
    * Encode method return value to JSON string.
    */
  def encodeResult(value: Any): String =
    if value == null then
      "null"
    else if value == () then
      // Unit return type
      "null"
    else
      returnWeaver.asInstanceOf[Weaver[Any]].toJson(value)

  private def decodeFromMap(map: Map[String, JSONValue]): Seq[Any] =
    val numParams = method.args.size
    val results   = Array.ofDim[Any](numParams)
    val found     = Array.fill(numParams)(false)

    // Match incoming keys using canonical names
    for (key, value) <- map do
      paramsByCanonicalName.get(CName.toCanonicalName(key)) match
        case Some((i, param, weaver)) =>
          try
            results(i) = weaver.asInstanceOf[Weaver[Any]].fromJSONValue(value)
            found(i) = true
          catch
            case e: Exception =>
              throw RPCStatus
                .INVALID_ARGUMENT_U2
                .newException(s"Failed to decode parameter '${param.name}': ${e.getMessage}", e)
        case None =>
        // Ignore unknown parameters

    // Fill missing with defaults or throw error
    for
      i <- 0 until numParams
      if !found(i)
    do
      val param = method.args(i)
      param.getDefaultValue match
        case Some(default) =>
          results(i) = default
        case None =>
          // Check if it's an Option type - use None as default
          if param.surface.isOption then
            results(i) = None
          else
            throw RPCStatus
              .INVALID_ARGUMENT_U2
              .newException(s"Missing required parameter: ${param.name}")

    results.toSeq

  end decodeFromMap

end MethodCodec
