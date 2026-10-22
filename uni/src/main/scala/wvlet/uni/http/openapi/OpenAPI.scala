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
package wvlet.uni.http.openapi

import wvlet.uni.http.{HttpMethod, Response}
import wvlet.uni.http.router.{HttpRequestMapper, PathComponent, Route, Router}
import wvlet.uni.json.JSON.*
import wvlet.uni.json.YAMLFormatter
import wvlet.uni.mcp.{JsonSchema, description}
import wvlet.uni.rx.Rx
import wvlet.uni.surface.{MethodParameter, OptionSurface, Primitive, StaticMethodParameter, Surface}

import scala.collection.mutable

/**
  * Configuration of [[OpenAPI]] generation.
  *
  * @param schemaPackagePrefix
  *   A package prefix stripped from schema names, e.g. `com.example.api.` turns
  *   `com.example.api.model.User` into `model.User`. Types outside it use their simple name
  * @param errorResponses
  *   Error statuses every operation lists, as shared `components/responses` entries
  */
case class OpenAPIConfig(
    title: String = "API",
    version: String = "0.0.1",
    schemaPackagePrefix: Option[String] = None,
    errorResponses: Seq[Int] = Seq(400, 500)
):
  def withTitle(t: String): OpenAPIConfig               = copy(title = t)
  def withVersion(v: String): OpenAPIConfig             = copy(version = v)
  def withSchemaPackagePrefix(p: String): OpenAPIConfig = copy(schemaPackagePrefix = Some(p))
  def noSchemaPackagePrefix(): OpenAPIConfig            = copy(schemaPackagePrefix = None)
  def withErrorResponses(codes: Int*): OpenAPIConfig    = copy(errorResponses = codes)

/**
  * Generate an OpenAPI 3.0.3 document from the routes of a [[Router]].
  *
  *   - Paths: `/users/:id` becomes `/users/{id}`.
  *   - `operationId` and `summary` are the method name, `description` comes from
  *     `@Endpoint(description = ...)`, and `tags` is the controller's simple name.
  *   - Parameters follow the router's binding rules: path parameters, then query parameters for
  *     primitives, and a `requestBody` (JSON and MessagePack) for the rest; see
  *     [[HttpRequestMapper]].
  *   - Object types become `$ref`s into `components/schemas`, described by the class's
  *     `@description`.
  *
  * {{{
  * val yaml = OpenAPI.of(Router.of[UserController], OpenAPIConfig().withTitle("Users")).toYAML
  * }}}
  */
case class OpenAPI(document: JSONObject):
  def toJSON: String = document.toJSON
  def toYAML: String = YAMLFormatter.toYaml(document.toJSON)

object OpenAPI:

  def of(router: Router, config: OpenAPIConfig = OpenAPIConfig()): OpenAPI = Generator(config)
    .generate(router.routes)

  def of(routers: Seq[Router], config: OpenAPIConfig): OpenAPI = Generator(config).generate(
    routers.flatMap(_.routes)
  )

  private val BodyMethods: Set[HttpMethod] = Set(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
  private val BodyContentTypes             = Seq("application/json", "application/x-msgpack")

  private class Generator(config: OpenAPIConfig):
    private val schemas     = mutable.LinkedHashMap.empty[String, JSONValue]
    private val schemaTypes = mutable.Map.empty[String, String]

    def generate(routes: Seq[Route]): OpenAPI =
      val duplicates = routes
        .groupBy(_.methodSurface.name)
        .collect {
          case (name, rs) if rs.size > 1 =>
            name
        }
      if duplicates.nonEmpty then
        throw IllegalArgumentException(
          s"Duplicate operationId (endpoint method name): ${duplicates.toSeq.sorted.mkString(", ")}"
        )
      val paths = routes
        .groupBy(r => openAPIPath(r.pathComponents))
        .toSeq
        .sortBy(_._1)
        .map { (path, rs) =>
          path ->
            JSONObject(rs.sortBy(_.method.name).map(r => r.method.name.toLowerCase -> operation(r)))
        }
      val doc = Seq.newBuilder[(String, JSONValue)]
      doc += "openapi" -> JSONString("3.0.3")
      doc += "info"    ->
        JSONObject(
          Seq("title" -> JSONString(config.title), "version" -> JSONString(config.version))
        )
      doc += "paths" -> JSONObject(paths)
      val components = Seq.newBuilder[(String, JSONValue)]
      if schemas.nonEmpty then
        components += "schemas" -> JSONObject(schemas.toSeq.sortBy(_._1))
      if config.errorResponses.nonEmpty then
        components += "responses" ->
          JSONObject(
            config
              .errorResponses
              .map { code =>
                code.toString -> JSONObject(Seq("description" -> JSONString(reasonOf(code))))
              }
          )
      doc += "components" -> JSONObject(components.result())
      OpenAPI(JSONObject(doc.result()))

    end generate

    private def openAPIPath(components: Seq[PathComponent]): String = components
      .map {
        case PathComponent.Literal(v) =>
          v
        case PathComponent.Parameter(name) =>
          s"{${name}}"
      }
      .mkString("/", "/", "")

    private def operation(r: Route): JSONObject =
      val ms        = r.methodSurface
      val pathNames =
        r.pathComponents
          .collect { case PathComponent.Parameter(n) =>
            n
          }
          .toSet
      val bodyParams = HttpRequestMapper.bodyParams(ms, pathNames)
      val whole      = HttpRequestMapper.wholeBodyParam(bodyParams)
      // The router reads non-primitive parameters from the body whatever the HTTP method, so they
      // are documented as the body even for GET/DELETE. Primitives are query parameters, except
      // in field mode for methods that carry a body, where every parameter is a body field
      val (queryParams, bodyFields) =
        whole match
          case Some(w) =>
            (bodyParams.filterNot(_ eq w), Seq.empty)
          case None if BodyMethods.contains(r.method) =>
            (Seq.empty, bodyParams)
          case None =>
            bodyParams.partition(p => HttpRequestMapper.isSimple(p.surface))
      val hasBody = whole.isDefined || bodyFields.nonEmpty

      val op = Seq.newBuilder[(String, JSONValue)]
      op += "summary" -> JSONString(ms.name)
      operationDescription(r).foreach(d => op += "description" -> JSONString(d))
      op += "operationId" -> JSONString(ms.name)
      val params =
        ms.args
          .filter(p => pathNames.contains(p.name))
          .map(parameter(_, "path", required = true)) ++
          queryParams.map(p => parameter(p, "query", required = isRequired(p)))
      if params.nonEmpty then
        op += "parameters" -> JSONArray(params.toIndexedSeq)
      if hasBody then
        val (schema, required) =
          whole match
            case Some(w) =>
              (schemaOf(w.surface), isRequired(w))
            case None =>
              (fieldsSchema(bodyFields), bodyFields.exists(isRequired))
        op += "requestBody" ->
          JSONObject(
            Seq(
              "content" ->
                JSONObject(BodyContentTypes.map(ct => ct -> JSONObject(Seq("schema" -> schema)))),
              "required" -> JSONBoolean(required)
            )
          )
      op += "responses" -> responses(ms.returnType)
      op += "tags"      -> JSONArray(IndexedSeq(JSONString(r.controllerSurface.name)))
      JSONObject(op.result())

    end operation

    // @Endpoint(description = ...), else the method's @description. Matching any annotation named
    // `description` by its `value` also accepts Airframe's @description, kept during a migration
    private def operationDescription(r: Route): Option[String] = r
      .methodSurface
      .annotations
      .find(_.fullName == classOf[wvlet.uni.http.router.Endpoint].getName)
      .flatMap(_.getAs[String]("description"))
      .filter(_.nonEmpty)
      .orElse(description.value(r.methodSurface.findAnnotation("description")))

    private def parameter(p: MethodParameter, in: String, required: Boolean): JSONObject =
      JSONObject(
        Seq(
          "name"     -> JSONString(p.name),
          "in"       -> JSONString(in),
          "required" -> JSONBoolean(required),
          "schema"   -> schemaOf(p.surface)
        )
      )

    // Mirrors HttpRequestMapper: a parameter without a default must be sent unless it is an Option,
    // or a collection the router passes as null when missing (unless marked @required)
    private def isRequired(p: MethodParameter): Boolean =
      val s          = p.surface
      val collection = s.isSeq || s.isMap || s.isArray
      !hasDefault(p) && !s.isOption && (!collection || p.isRequired)

    // Method-argument defaults are evaluated on a controller instance, which is not available
    // here; their accessor tells that one exists
    private def hasDefault(p: MethodParameter): Boolean =
      p.getDefaultValue.isDefined || (
        p match
          case s: StaticMethodParameter =>
            s.methodArgAccessor.isDefined
          case _ =>
            false
      )

    private def fieldsSchema(params: Seq[MethodParameter]): JSONObject =
      val fields = Seq.newBuilder[(String, JSONValue)]
      fields += "type"       -> JSONString("object")
      fields += "properties" -> JSONObject(params.map(p => p.name -> schemaOf(p.surface)))
      val required = params.filter(isRequired).map(p => JSONString(p.name))
      if required.nonEmpty then
        fields += "required" -> JSONArray(required.toIndexedSeq)
      JSONObject(fields.result())

    private def responses(returnType: Surface): JSONObject =
      val body      = peel(returnType)
      val noContent = "204" -> JSONObject(Seq("description" -> JSONString("No Content")))
      val success   =
        if body == Primitive.Unit then
          Seq(noContent)
        else
          val ok = Seq.newBuilder[(String, JSONValue)]
          ok += "description" -> JSONString("OK")
          content(body).foreach(c => ok += "content" -> c)
          // The router answers None with 204
          if returnsOption(returnType) then
            Seq("200" -> JSONObject(ok.result()), noContent)
          else
            Seq("200" -> JSONObject(ok.result()))
      val errors = config
        .errorResponses
        .map(code =>
          code.toString -> JSONObject(Seq("$ref" -> JSONString(s"#/components/responses/${code}")))
        )
      JSONObject(success ++ errors)

    // Mirrors ResponseConverter: String is text, bytes are binary, a Response has no schema, and
    // other values are JSON
    private def content(s: Surface): Option[JSONObject] =
      if s.rawType == classOf[Response] then
        None
      else if s == Primitive.String then
        Some(media("text/plain", JSONObject(Seq("type" -> JSONString("string")))))
      else if s.isArray && s.typeArgs.headOption.contains(Primitive.Byte) then
        Some(
          media(
            "application/octet-stream",
            JSONObject(Seq("type" -> JSONString("string"), "format" -> JSONString("binary")))
          )
        )
      else
        Some(media("application/json", schemaOf(s)))

    private def media(contentType: String, schema: JSONObject): JSONObject = JSONObject(
      Seq(contentType -> JSONObject(Seq("schema" -> schema)))
    )

    // Rx and Option wrappers do not change the response body (see RouterHandler)
    private def peel(s: Surface): Surface =
      val afterRx =
        if classOf[Rx[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty then
          s.typeArgs.head
        else
          s
      afterRx match
        case o: OptionSurface =>
          peel(o.elementSurface)
        case other =>
          other

    private def returnsOption(s: Surface): Boolean =
      val afterRx =
        if classOf[Rx[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty then
          s.typeArgs.head
        else
          s
      afterRx.isOption

    private val schemaOptions = JsonSchema.Options(
      refFor = refFor,
      formats = true,
      defaults = true,
      classDescriptions = true
    )

    private def schemaOf(s: Surface): JSONObject = JsonSchema.ofSurface(s, Set.empty, schemaOptions)

    // Register an object type in components/schemas and refer to it. The placeholder entry stops
    // recursion for self-referential types
    private def refFor(s: Surface): Option[JSONObject] =
      val name = schemaName(s)
      schemaTypes.get(name) match
        case Some(other) if other != s.fullName =>
          throw IllegalArgumentException(
            s"Types ${other} and ${s
                .fullName} have the same OpenAPI schema name ${name}; set OpenAPIConfig.withSchemaPackagePrefix so their names differ"
          )
        case _ =>
          schemaTypes(name) = s.fullName
      if !schemas.contains(name) then
        schemas(name) = JSONObject(Seq.empty)
        schemas(name) = JsonSchema.objectSchemaOf(s, Set.empty, schemaOptions)
      Some(JSONObject(Seq("$ref" -> JSONString(s"#/components/schemas/${name}"))))

    // Component names may only use [A-Za-z0-9._-], so generic types such as Page[User] become
    // Page_User_
    private def schemaName(s: Surface): String =
      val full = s.fullName.replaceAll("\\$", ".")
      val name =
        config.schemaPackagePrefix match
          case Some(prefix) if full.startsWith(prefix) =>
            full.replace(prefix, "")
          case _ =>
            s.name
      name.replaceAll("[^A-Za-z0-9._-]", "_")

    private def reasonOf(code: Int): String = wvlet.uni.http.HttpStatus.ofCode(code).reason

  end Generator

end OpenAPI
