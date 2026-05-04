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
package wvlet.uni.http.router

import wvlet.uni.http.{HttpContent, Response}
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver

/**
  * Converts controller method return values to HTTP responses.
  *
  * Supports the following return types:
  *   - Response: returned as-is
  *   - Rx[Response]: returned as-is
  *   - Rx[A]: converted to Rx[Response] with JSON body
  *   - JSONValue: converted to Response with JSON body
  *   - String: converted to Response with text body
  *   - Unit: converted to Response with no content
  *   - Other types: serialized to JSON using Weaver
  */
object ResponseConverter:

  /**
    * Convert a method return value to an Rx[Response].
    *
    * @param result
    *   The return value from a controller method
    * @return
    *   An Rx that emits the HTTP response
    */
  def toResponse(result: Any): Rx[Response] = toResponse(result, None)

  /**
    * Convert a method return value to an Rx[Response] using the given Weaver to encode case classes
    * and other complex types as JSON.
    *
    * The weaver is expected to be derived for the inner element type, after peeling [[Rx]] and
    * [[Option]] from the controller method's declared return type.
    *
    * @param result
    *   The return value from a controller method
    * @param returnWeaver
    *   Weaver for the inner element type
    * @return
    *   An Rx that emits the HTTP response
    */
  def toResponse(result: Any, returnWeaver: Weaver[?]): Rx[Response] = toResponse(
    result,
    Some(returnWeaver)
  )

  private def toResponse(result: Any, weaverOpt: Option[Weaver[?]]): Rx[Response] =
    result match
      case r: Response =>
        Rx.single(r)
      case rx: Rx[?] =>
        rx.map(convertToResponse(_, weaverOpt))
      case other =>
        Rx.single(convertToResponse(other, weaverOpt))

  /**
    * Convert a single value to an HTTP response.
    */
  private def convertToResponse(value: Any, weaverOpt: Option[Weaver[?]]): Response =
    value match
      case r: Response =>
        r
      case null =>
        Response.noContent
      case () =>
        Response.noContent
      case s: String =>
        Response.ok.withTextContent(s)
      case json: JSONValue =>
        Response.ok.withContent(HttpContent.json(json))
      case bytes: Array[Byte] =>
        Response.ok.withBytesContent(bytes)
      case opt: Option[?] =>
        opt match
          case Some(v) =>
            convertToResponse(v, weaverOpt)
          case None =>
            Response.noContent
      case other =>
        weaverOpt match
          case Some(weaver) =>
            jsonOrText(other, weaver.asInstanceOf[Weaver[Any]].toJson(other))
          case None =>
            other match
              case seq: Seq[?] =>
                Response.ok.withContent(HttpContent.json(seqToJson(seq)))
              case map: Map[?, ?] =>
                Response.ok.withContent(HttpContent.json(mapToJson(map)))
              case _ =>
                jsonOrText(other, toJsonString(other))

  private def jsonOrText(value: Any, encode: => String): Response =
    try
      Response.ok.withJsonContent(encode)
    catch
      case _: Exception =>
        Response.ok.withTextContent(value.toString)

  /**
    * Convert a sequence to a JSON array string.
    */
  private def seqToJson(seq: Seq[?]): String =
    val elements = seq.map(elementToJsonString)
    s"[${elements.mkString(",")}]"

  /**
    * Convert a map to a JSON object string.
    */
  private def mapToJson(map: Map[?, ?]): String =
    val entries = map
      .toSeq
      .map { case (k, v) =>
        val keyStr   = escapeJsonString(k.toString)
        val valueStr = elementToJsonString(v)
        s"\"${keyStr}\":${valueStr}"
      }
    s"{${entries.mkString(",")}}"

  /**
    * Convert a single element to a JSON string representation.
    */
  private def elementToJsonString(value: Any): String =
    value match
      case null =>
        "null"
      case s: String =>
        s"\"${escapeJsonString(s)}\""
      case n: Number =>
        n.toString
      case b: Boolean =>
        b.toString
      case json: JSONValue =>
        json.toJSON
      case seq: Seq[?] =>
        seqToJson(seq)
      case map: Map[?, ?] =>
        mapToJson(map)
      case opt: Option[?] =>
        opt match
          case Some(v) =>
            elementToJsonString(v)
          case None =>
            "null"
      case other =>
        toJsonString(other)

  /**
    * Escape special characters in a JSON string.
    */
  private def escapeJsonString(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

  /**
    * Convert an object to a JSON string. Falls back to toString for non-standard types.
    *
    * Note: For proper JSON serialization of case classes, use Weaver with a derived codec. This
    * method provides a simple fallback that works across all platforms (JVM, JS, Native).
    */
  private def toJsonString(value: Any): String =
    // For cross-platform compatibility, we use toString as fallback
    // Users should provide proper Weaver codecs for complex types
    s"\"${escapeJsonString(value.toString)}\""

end ResponseConverter
