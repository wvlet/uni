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

import wvlet.uni.json.JSON.{JSONArray, JSONObject, JSONString, JSONValue}
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
private[mcp] object JsonSchema:

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

  def ofSurface(surface: Surface, seen: Set[String]): JSONObject =
    val s = surface.dealias
    s match
      case o if o.isOption =>
        ofSurface(o.typeArgs.head, seen)
      case p if p.isPrimitive =>
        primitiveSchema(p)
      case _: EnumSurface | _: JavaEnumSurface =>
        // EnumSurface carries only a string decoder, not the case names; emit a plain string
        // schema with the enum type name as a hint
        JSONObject(
          Seq("type" -> JSONString("string"), "description" -> JSONString(s"${s.name} enum value"))
        )
      case a if a.isArray || a.isSeq || isSet(a) =>
        val element = elementSurface(a)
        JSONObject(Seq("type" -> JSONString("array"), "items" -> ofSurface(element, seen)))
      case m if m.isMap =>
        val valueSchema =
          if m.typeArgs.size >= 2 then
            ofSurface(m.typeArgs(1), seen)
          else
            JSONObject(Seq.empty)
        JSONObject(Seq("type" -> JSONString("object"), "additionalProperties" -> valueSchema))
      case c if c.params.nonEmpty =>
        if seen.contains(c.fullName) then
          // Break recursion for self-referential types with an unconstrained object schema
          JSONObject(Seq("type" -> JSONString("object")))
        else
          val nextSeen   = seen + c.fullName
          val properties = c
            .params
            .map { p =>
              p.name -> withDescription(ofSurface(p.surface, nextSeen), descriptionOf(p))
            }
          val required = c.params.filter(isRequiredParam).map(p => JSONString(p.name))
          objectSchema(properties, required)
      case _ =>
        // Opaque types (Instant, UUID, ULID, ...) are serialized as strings by their weavers
        JSONObject(Seq("type" -> JSONString("string")))
    end match

  end ofSurface

  private def primitiveSchema(s: Surface): JSONObject =
    val tpe =
      s match
        case Primitive.Int | Primitive.Long | Primitive.Short | Primitive.Byte | Primitive.BigInt |
            Primitive.BigInteger =>
          "integer"
        case Primitive.Float | Primitive.Double =>
          "number"
        case Primitive.Boolean =>
          "boolean"
        case _ =>
          // String, Char, and any other primitive-like value serialized as a string
          "string"
    JSONObject(Seq("type" -> JSONString(tpe)))

  private def objectSchema(
      properties: Seq[(String, JSONValue)],
      required: Seq[JSONString]
  ): JSONObject =
    val fields = Seq.newBuilder[(String, JSONValue)]
    fields += "type"       -> JSONString("object")
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
