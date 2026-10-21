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

import wvlet.uni.http.{Request, RequestAttributes}
import wvlet.uni.surface.{MethodParameter, MethodSurface, Primitive, Surface}

/**
  * Maps HTTP request data to method parameters.
  *
  * The mapping order is:
  *   1. Path parameters (from URL path, e.g., `:id` -> "123")
  *   2. Query parameters (from URL query string)
  *   3. Default values (if parameter has a default)
  *   4. The Request object itself (if parameter type is Request), or its attributes (if parameter
  *      type is RequestAttributes)
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
      controllerOpt: Option[Any] = None
  ): Seq[Any] = methodSurface
    .args
    .map { param =>
      bindParameter(request, param, pathParams, controllerOpt)
    }

  /**
    * Bind a single parameter from the request.
    */
  private def bindParameter(
      request: Request,
      param: MethodParameter,
      pathParams: Map[String, String],
      controllerOpt: Option[Any]
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
    // Try method arg default value (requires controller instance)
    else
      controllerOpt.flatMap(ctrl => param.getMethodArgDefaultValue(ctrl)) match
        case Some(defaultValue) =>
          defaultValue
        // Try constructor default value
        case None if param.getDefaultValue.isDefined =>
          param.getDefaultValue.get
        // For required parameters without a value, throw an exception
        case None if param.isRequired =>
          throw HttpRequestMappingException(s"Required parameter '${paramName}' is missing")
        // Return null for optional parameters without a value
        case None =>
          null

  end bindParameter

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
  * Exception thrown when request parameter mapping fails.
  */
case class HttpRequestMappingException(message: String) extends Exception(message)
