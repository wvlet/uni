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

import wvlet.uni.json.JSON.{
  JSONArray,
  JSONBoolean,
  JSONDouble,
  JSONLong,
  JSONObject,
  JSONString,
  JSONValue
}
import wvlet.uni.surface.{
  EnumSurface,
  JavaEnumSurface,
  MethodSurface,
  Parameter,
  Primitive,
  Surface
}

/**
  * Derives a JSON Schema for MCP tool inputs from Surface metadata.
  *
  * The mapping mirrors how [[wvlet.uni.weaver.Weaver]] serializes each shape, so a client sending
  * arguments that validate against the schema will decode successfully:
  *   - primitives → integer/number/boolean/string
  *   - Option[A] / defaulted params → schema of A, omitted from `required`
  *   - Seq/Set/Array → array with `items`; Map → object with `additionalProperties`
  *   - case classes → nested object schemas from constructor params
  *   - enums → string (Surface does not expose enum case names at runtime, so values cannot be
  *     enumerated cross-platform; weavers decode the case name string)
  */
private[uni] object JsonSchema:

  /**
    * Schema for a tool's input: one JSON object whose properties are the method's parameters.
    *
    * @param methodOwner
    *   the service instance, needed to detect method-argument default values (compiled as instance
    *   methods) so defaulted parameters are not marked required
    */
  def ofMethodArgs(method: MethodSurface, methodOwner: Option[Any] = None): JSONObject =
    val properties = method
      .args
      .map { p =>
        p.name -> withDescription(ofSurface(p.surface, Set.empty), descriptionOf(p))
      }
    // A parameter is required when the decoder cannot fill it in: no default value and not
    // Option (mirrors MethodCodec.decodeFromMap)
    val required = method
      .args
      .filter(p => p.resolveDefaultValue(methodOwner).isEmpty && !p.surface.isOption)
      .map(p => JSONString(p.name))
    objectSchema(properties, required)

  // Constructor-parameter variant of the required check above: case-class field defaults are
  // captured statically, so no owner instance is needed
  private def isRequiredParam(p: Parameter): Boolean =
    p.getDefaultValue.isEmpty && !p.surface.isOption

  /**
    * Options for schemas outside MCP, e.g. OpenAPI. MCP uses the defaults, which keep its tool
    * schemas unchanged.
    *
    * @param refFor
    *   Returns a reference (e.g. an OpenAPI `$ref`) to use instead of inlining an object type, or
    *   None to inline it
    * @param formats
    *   Add `format` (int32, int64, float, double) to numeric schemas
    * @param defaults
    *   Add `default` for object fields whose default is a number (except BigInt), Boolean, Char, or
    *   String
    * @param classDescriptions
    *   Add `description` to object schemas from the class's `@description`
    */
  case class Options(
      refFor: Surface => Option[JSONObject] = _ => None,
      formats: Boolean = false,
      defaults: Boolean = false,
      classDescriptions: Boolean = false
  )

  def ofSurface(surface: Surface, seen: Set[String], options: Options = Options()): JSONObject =
    val s = surface.dealias
    s match
      case o if o.isOption =>
        ofSurface(o.typeArgs.head, seen, options)
      case p if p.isPrimitive =>
        primitiveSchema(p, options.formats)
      case _: EnumSurface | _: JavaEnumSurface =>
        // EnumSurface carries only a string decoder, not the case names; emit a plain string
        // schema with the enum type name as a hint
        JSONObject(
          Seq("type" -> JSONString("string"), "description" -> JSONString(s"${s.name} enum value"))
        )
      case a if a.isArray || a.isSeq || isSet(a) =>
        val element = elementSurface(a)
        JSONObject(Seq("type" -> JSONString("array"), "items" -> ofSurface(element, seen, options)))
      case m if m.isMap =>
        val valueSchema =
          if m.typeArgs.size >= 2 then
            ofSurface(m.typeArgs(1), seen, options)
          else
            JSONObject(Seq.empty)
        JSONObject(Seq("type" -> JSONString("object"), "additionalProperties" -> valueSchema))
      case c if c.params.nonEmpty =>
        options
          .refFor(c)
          .getOrElse {
            if seen.contains(c.fullName) then
              // Break recursion for self-referential types with an unconstrained object schema
              JSONObject(Seq("type" -> JSONString("object")))
            else
              objectSchemaOf(c, seen + c.fullName, options)
          }
      case _ =>
        // Opaque types (Instant, UUID, ULID, ...) are serialized as strings by their weavers
        JSONObject(Seq("type" -> JSONString("string")))
    end match

  end ofSurface

  /** The schema of an object type's fields, always inlined at the top level */
  def objectSchemaOf(
      c: Surface,
      seen: Set[String] = Set.empty,
      options: Options = Options()
  ): JSONObject =
    val properties = c
      .params
      .map { p =>
        val schema = withDescription(ofSurface(p.surface, seen, options), descriptionOf(p))
        p.name -> (
          if options.defaults then
            withDefault(schema, p)
          else
            schema
        )
      }
    val required          = c.params.filter(isRequiredParam).map(p => JSONString(p.name))
    val schemaDescription =
      if options.classDescriptions then
        description.value(c.dealias.findAnnotation("description"))
      else
        None
    objectSchema(properties, required, schemaDescription)

  private def primitiveSchema(s: Surface, formats: Boolean): JSONObject =
    val (tpe, format) =
      s match
        case Primitive.Int | Primitive.Short | Primitive.Byte =>
          ("integer", Some("int32"))
        case Primitive.Long =>
          ("integer", Some("int64"))
        case Primitive.BigInt | Primitive.BigInteger =>
          ("integer", None)
        case Primitive.Float =>
          ("number", Some("float"))
        case Primitive.Double =>
          ("number", Some("double"))
        case Primitive.Boolean =>
          ("boolean", None)
        case _ =>
          // String, Char, and any other primitive-like value serialized as a string
          ("string", None)
    val fields =
      Seq("type" -> JSONString(tpe)) ++
        format.filter(_ => formats).map(f => "format" -> JSONString(f))
    JSONObject(fields)

  // A number (except BigInt/BigInteger), Boolean, Char, or String default as a JSON value; other
  // defaults (None, collections, objects) are left out
  private def withDefault(schema: JSONObject, p: Parameter): JSONObject =
    val default: Option[JSONValue] = p
      .getDefaultValue
      .collect {
        case v: String =>
          JSONString(v)
        case v: Boolean =>
          JSONBoolean(v)
        case v: (Int | Long | Short | Byte) =>
          JSONLong(v.toString.toLong)
        case v: Double if !v.isNaN && !v.isInfinite =>
          JSONDouble(v)
        case v: Float if !v.isNaN && !v.isInfinite =>
          // Via its decimal form, so 0.1f stays 0.1 rather than 0.10000000149011612
          JSONDouble(v.toString.toDouble)
        case v: Char =>
          JSONString(v.toString)
      }
    default.fold(schema)(d => JSONObject(schema.v :+ ("default" -> d)))

  private def objectSchema(
      properties: Seq[(String, JSONValue)],
      required: Seq[JSONString],
      schemaDescription: Option[String] = None
  ): JSONObject =
    val fields = Seq.newBuilder[(String, JSONValue)]
    fields += "type" -> JSONString("object")
    schemaDescription.foreach(d => fields += "description" -> JSONString(d))
    fields += "properties" -> JSONObject(properties)
    if required.nonEmpty then
      fields += "required" -> JSONArray(required.toIndexedSeq)
    JSONObject(fields.result())

  private def isSet(s: Surface): Boolean = classOf[Set[?]].isAssignableFrom(s.rawType)

  private def elementSurface(s: Surface): Surface =
    s match
      case a: wvlet.uni.surface.ArraySurface =>
        a.elementSurface
      case _ if s.typeArgs.nonEmpty =>
        s.typeArgs.head
      case _ =>
        Surface.of[Any]

  private def descriptionOf(p: Parameter): Option[String] = description.value(
    p.findAnnotation("description")
  )

  private def withDescription(schema: JSONObject, description: Option[String]): JSONObject =
    description match
      case Some(d) =>
        JSONObject(schema.v.filterNot(_._1 == "description") :+ ("description" -> JSONString(d)))
      case None =>
        schema

end JsonSchema
