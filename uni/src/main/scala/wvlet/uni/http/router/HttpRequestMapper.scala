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

import wvlet.uni.http.{HttpStatus, Request, RequestAttributes}
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONObject, JSONValue}
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Value.{MapValue, StringValue}
import wvlet.uni.surface.{MethodParameter, MethodSurface, Primitive, Surface}
import wvlet.uni.weaver.Weaver

import scala.util.control.NonFatal

/**
  * Maps HTTP request data to method parameters.
  *
  * The mapping order is:
  *   1. The Request object itself (if parameter type is Request), or its attributes (if parameter
  *      type is RequestAttributes)
  *   2. Path parameters (from URL path, e.g., `:id` -> "123")
  *   3. Query parameters (from URL query string)
  *   4. The request body, decoded with a Weaver (JSON or MessagePack)
  *   5. Default values (if parameter has a default)
  *
  * Body rules, decided by the method signature: when exactly one parameter other than `Request`,
  * `RequestAttributes`, and path parameters is not a primitive, the whole body decodes into it (a
  * collection or map only when it is the only such parameter). Otherwise the body must be an
  * object, and each parameter is read from the field of its name.
  */
class HttpRequestMapper:

  /**
    * Bind request data to method parameters.
    *
    * @param request
    *   The incoming HTTP request
    * @param methodSurface
    *   The method surface describing the endpoint
    * @param pathParams
    *   Map of path parameter names to values
    * @param controllerOpt
    *   Optional controller instance for accessing method arg default values
    * @return
    *   A sequence of argument values in the order expected by the method
    */
  def bindParameters(
      request: Request,
      methodSurface: MethodSurface,
      pathParams: Map[String, String],
      controllerOpt: Option[Any] = None,
      paramWeavers: Map[String, Weaver[?]] = Map.empty
  ): Seq[Any] =
    val bodyParams = methodSurface
      .args
      .filter { p =>
        val t = p.surface.rawType
        t != classOf[Request] && t != classOf[RequestAttributes] && !pathParams.contains(p.name)
      }
    // A method that takes the Request may read the body itself, so a body that does not fit the
    // other parameters is an error only when a required one is left without a value
    val readsRawBody = methodSurface.args.exists(_.surface.rawType == classOf[Request])
    val body         = RequestBody(request, bodyParams, paramWeavers, strict = !readsRawBody)
    methodSurface
      .args
      .map { param =>
        bindParameter(request, param, pathParams, controllerOpt, body)
      }

  /**
    * Bind a single parameter from the request.
    */
  private def bindParameter(
      request: Request,
      param: MethodParameter,
      pathParams: Map[String, String],
      controllerOpt: Option[Any],
      body: RequestBody
  ): Any =
    val paramName    = param.name
    val paramSurface = param.surface

    // Check if the parameter is the Request type itself
    if paramSurface.rawType == classOf[Request] then
      request
    else if paramSurface.rawType == classOf[RequestAttributes] then
      request.attributes
    // Try path parameters first
    else if pathParams.contains(paramName) then
      convertParam(paramName, pathParams(paramName), param.surface)
    // Try query parameters
    else if request.getQueryParam(paramName).isDefined then
      val queryValue = request.getQueryParam(paramName).get
      convertParam(paramName, queryValue, param.surface)
    else if body.value(paramName).isDefined then
      body.value(paramName).get
    // Try method arg default value (requires controller instance)
    else
      controllerOpt.flatMap(ctrl => param.getMethodArgDefaultValue(ctrl)) match
        case Some(defaultValue) =>
          defaultValue
        // Try constructor default value
        case None if param.getDefaultValue.isDefined =>
          param.getDefaultValue.get
        // A body we could not read explains the missing value better than the parameter name
        case None if isRequired(param) && body.errorFor(paramName).isDefined =>
          throw body.errorFor(paramName).get
        // For required parameters without a value, throw an exception
        case None if isRequired(param) =>
          throw HttpRequestMappingException(s"Required parameter '${paramName}' is missing")
        // Return null for optional parameters without a value
        case None =>
          null
    end if

  end bindParameter

  // An object parameter (e.g. a case class read from the body) has no usable null value, so it is
  // required even without @required. Options and collections keep their null default
  private def isRequired(param: MethodParameter): Boolean =
    val s = param.surface
    param.isRequired || !(s.isPrimitive || s.isOption || s.isSeq || s.isMap || s.isArray)

  /**
    * Convert a client-supplied value, reporting a malformed one (e.g. `"abc"` for an Int) as a bad
    * request rather than a server error.
    */
  private def convertParam(paramName: String, value: String, surface: Surface): Any =
    try
      convertValue(value, surface)
    catch
      case _: IllegalArgumentException =>
        throw HttpRequestMappingException(
          s"Invalid value for parameter '${paramName}': expected ${surface.name}"
        )

  /**
    * Convert a string value to the expected type based on Surface.
    */
  private def convertValue(value: String, surface: Surface): Any =
    surface match
      case Primitive.String =>
        value
      case Primitive.Int =>
        value.toInt
      case Primitive.Long =>
        value.toLong
      case Primitive.Double =>
        value.toDouble
      case Primitive.Float =>
        value.toFloat
      case Primitive.Boolean =>
        value.toBoolean
      case Primitive.Short =>
        value.toShort
      case Primitive.Byte =>
        value.toByte
      case _ if surface.isOption =>
        // Handle Option types
        if value.isEmpty then
          None
        else
          surface.typeArgs.headOption match
            case Some(innerSurface) =>
              Some(convertValue(value, innerSurface))
            case None =>
              Some(value)
      case _ =>
        // For other types, return as string and let the caller handle conversion
        value
    end match

  end convertValue

end HttpRequestMapper

/**
  * Parameter values decoded from a request body, computed on first use.
  *
  * Error messages never echo the body, which may hold user data.
  *
  * @param strict
  *   Raise a body error as soon as a parameter looks up its value. Otherwise an error is raised
  *   only for a required parameter left without a value (see [[errorFor]])
  */
private class RequestBody(
    request: Request,
    bodyParams: Seq[MethodParameter],
    paramWeavers: Map[String, Weaver[?]],
    strict: Boolean
):
  private enum Format:
    case Json,
      MsgPack

  /** The decoded value of a parameter, if the body has one */
  def value(name: String): Option[Any] =
    if strict then
      errorFor(name)
        .filterNot(_.status == HttpStatus.UnsupportedMediaType_415)
        .foreach(e => throw e)
    decoded.values.get(name)

  /** Why the body has no value for a parameter, if it could not be read */
  def errorFor(name: String): Option[HttpRequestMappingException] = decoded
    .fieldErrors
    .get(name)
    .orElse(decoded.bodyError)

  private case class Decoded(
      values: Map[String, Any] = Map.empty,
      fieldErrors: Map[String, HttpRequestMappingException] = Map.empty,
      bodyError: Option[HttpRequestMappingException] = None
  )

  // A body without a content type may be anything, e.g. text a controller reads from the Request,
  // so it is decoded only when it parses
  private def lenient: Boolean = request.contentType.isEmpty

  private def malformed(message: String): Decoded =
    if lenient then
      Decoded()
    else
      Decoded(bodyError = Some(HttpRequestMappingException(message)))

  private lazy val decoded: Decoded =
    if request.content.isEmpty || bodyParams.isEmpty then
      Decoded()
    else
      val format =
        request.contentType match
          case None =>
            Some(Format.Json)
          case Some(ct) if ct.isJson =>
            Some(Format.Json)
          case Some(ct)
              if ct.fullType == "application/msgpack" || ct.fullType == "application/x-msgpack" =>
            Some(Format.MsgPack)
          case Some(_) =>
            None
      format match
        case None =>
          Decoded(bodyError =
            Some(
              HttpRequestMappingException.unsupportedMediaType(
                s"Unsupported content type: ${request.contentType.get.fullType}"
              )
            )
          )
        case Some(f) =>
          wholeBodyParam match
            case Some(p) =>
              decodeWhole(p, f)
            case None =>
              decodeFields(f)

  // The parameter that takes the whole body: the only one that is not a primitive. A collection or
  // map takes it only when it is the only body parameter, since an object body is far more common
  // than an array or map body next to other parameters
  private def wholeBodyParam: Option[MethodParameter] =
    bodyParams.filterNot(p => isSimple(p.surface)) match
      case Seq(p) if bodyParams.size == 1 || !isCollection(p.surface) =>
        Some(p)
      case _ =>
        None

  private def isCollection(s: Surface): Boolean = s.isSeq || s.isMap || s.isArray

  private def weaverOf(p: MethodParameter): Weaver[Any] = paramWeavers
    .getOrElse(p.name, Weaver.fromSurface(p.surface))
    .asInstanceOf[Weaver[Any]]

  private def invalidField(name: String) = HttpRequestMappingException(
    s"Invalid request body for parameter '${name}'"
  )

  private def decodeWhole(p: MethodParameter, f: Format): Decoded =
    try
      val v =
        f match
          case Format.Json =>
            weaverOf(p).fromJson(request.content.toContentString)
          case Format.MsgPack =>
            weaverOf(p).unweave(request.content.toContentBytes)
      Decoded(values = Map(p.name -> v))
    catch
      case NonFatal(_) if lenient =>
        Decoded()
      case NonFatal(_) =>
        Decoded(fieldErrors = Map(p.name -> invalidField(p.name)))

  // Decode each field with its parameter's Weaver: JSON fields as JSONValue, MessagePack fields
  // from their own bytes, so binary and extension values survive
  private def decodeFields(f: Format): Decoded =
    val fields: Option[Map[String, Weaver[Any] => Any]] =
      try
        f match
          case Format.Json =>
            JSON.parse(request.content.toContentString) match
              case obj: JSONObject =>
                Some(obj.v.toMap.map((k, v) => k -> ((w: Weaver[Any]) => w.fromJSONValue(v))))
              case _ =>
                None
          case Format.MsgPack =>
            MessagePack.newUnpacker(request.content.toContentBytes).unpackValue match
              case m: MapValue =>
                Some(
                  m.entries
                    .collect { case (StringValue(k), v) =>
                      val bytes = MessagePack.newPacker().packValue(v).toByteArray
                      k -> ((w: Weaver[Any]) => w.unweave(bytes))
                    }
                )
              case _ =>
                None
      catch
        case NonFatal(_) =>
          return malformed("Malformed request body")
    fields match
      case None =>
        malformed("Request body must be an object")
      case Some(fields) =>
        val results = bodyParams.flatMap { p =>
          fields
            .get(p.name)
            .map { decode =>
              try
                p.name -> Right(decode(weaverOf(p)))
              catch
                case NonFatal(_) =>
                  p.name -> Left(invalidField(p.name))
            }
        }
        Decoded(
          values =
            results
              .collect { case (k, Right(v)) =>
                k -> v
              }
              .toMap,
          fieldErrors =
            results
              .collect { case (k, Left(e)) =>
                k -> e
              }
              .toMap
        )

  end decodeFields

  private def isSimple(surface: Surface): Boolean =
    surface.isPrimitive || (surface.isOption && surface.typeArgs.headOption.exists(_.isPrimitive))

end RequestBody

/**
  * Exception thrown when request parameter mapping fails. The response status is 400, or 415 for a
  * body in an unsupported content type.
  */
case class HttpRequestMappingException(message: String) extends Exception(message):
  def status: HttpStatus = HttpStatus.BadRequest_400

object HttpRequestMappingException:
  def unsupportedMediaType(message: String): HttpRequestMappingException =
    new HttpRequestMappingException(message):
      override def status: HttpStatus = HttpStatus.UnsupportedMediaType_415
