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

import wvlet.uni.json.JSON.{JSONArray, JSONObject, JSONString}
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

case class GeoPoint(lat: Double, lon: Double)
case class Address(street: String, geo: GeoPoint, label: Option[String] = None)

enum Color:
  case Red,
    Green,
    Blue

trait SchemaFixture:
  def scalars(i: Int, l: Long, d: Double, f: Float, b: Boolean, s: String): Unit
  def optional(name: Option[String], limit: Int = 10): Unit
  def collections(tags: Seq[String], scores: Map[String, Int]): Unit
  def nested(address: Address): Unit
  def colored(color: Color): Unit

object SchemaFixtureImpl extends SchemaFixture:
  def scalars(i: Int, l: Long, d: Double, f: Float, b: Boolean, s: String): Unit = ()
  def optional(name: Option[String], limit: Int): Unit                           = ()
  def collections(tags: Seq[String], scores: Map[String, Int]): Unit             = ()
  def nested(address: Address): Unit                                             = ()
  def colored(color: Color): Unit                                                = ()

class JsonSchemaTest extends UniTest:

  private val methods = Surface.methodsOf[SchemaFixture].map(m => m.name -> m).toMap

  private def schemaOf(methodName: String): JSONObject = JsonSchema.ofMethodArgs(
    methods(methodName),
    Some(SchemaFixtureImpl)
  )

  private def propertyType(schema: JSONObject, name: String): Option[JSONString] = schema
    .get("properties")
    .collect { case o: JSONObject =>
      o
    }
    .flatMap(_.get(name))
    .collect { case o: JSONObject =>
      o
    }
    .flatMap(_.get("type"))
    .collect { case s: JSONString =>
      s
    }

  private def requiredNames(schema: JSONObject): Set[String] = schema
    .get("required")
    .collect { case a: JSONArray =>
      a.v
        .collect { case JSONString(s) =>
          s
        }
        .toSet
    }
    .getOrElse(Set.empty)

  test("map scalar types to JSON Schema types") {
    val schema = schemaOf("scalars")
    schema.get("type") shouldBe Some(JSONString("object"))
    propertyType(schema, "i") shouldBe Some(JSONString("integer"))
    propertyType(schema, "l") shouldBe Some(JSONString("integer"))
    propertyType(schema, "d") shouldBe Some(JSONString("number"))
    propertyType(schema, "f") shouldBe Some(JSONString("number"))
    propertyType(schema, "b") shouldBe Some(JSONString("boolean"))
    propertyType(schema, "s") shouldBe Some(JSONString("string"))
    requiredNames(schema) shouldBe Set("i", "l", "d", "f", "b", "s")
  }

  test("Option and defaulted parameters are optional") {
    val schema = schemaOf("optional")
    // Option unwraps to the element type
    propertyType(schema, "name") shouldBe Some(JSONString("string"))
    propertyType(schema, "limit") shouldBe Some(JSONString("integer"))
    requiredNames(schema) shouldBe Set.empty
  }

  test("Seq maps to array with items; Map to object with additionalProperties") {
    val schema = schemaOf("collections")
    propertyType(schema, "tags") shouldBe Some(JSONString("array"))
    propertyType(schema, "scores") shouldBe Some(JSONString("object"))

    val properties = schema.get("properties").get.asInstanceOf[JSONObject]
    val tags       = properties.get("tags").get.asInstanceOf[JSONObject]
    tags.get("items").get.asInstanceOf[JSONObject].get("type") shouldBe Some(JSONString("string"))
    val scores = properties.get("scores").get.asInstanceOf[JSONObject]
    scores.get("additionalProperties").get.asInstanceOf[JSONObject].get("type") shouldBe
      Some(JSONString("integer"))
  }

  test("case class parameters become nested object schemas") {
    val schema  = schemaOf("nested")
    val address = schema
      .get("properties")
      .get
      .asInstanceOf[JSONObject]
      .get("address")
      .get
      .asInstanceOf[JSONObject]
    address.get("type") shouldBe Some(JSONString("object"))
    val addressProps = address.get("properties").get.asInstanceOf[JSONObject]
    addressProps.get("street").get.asInstanceOf[JSONObject].get("type") shouldBe
      Some(JSONString("string"))
    // Nested one more level
    val geo = addressProps.get("geo").get.asInstanceOf[JSONObject]
    geo.get("type") shouldBe Some(JSONString("object"))
    geo
      .get("properties")
      .get
      .asInstanceOf[JSONObject]
      .get("lat")
      .get
      .asInstanceOf[JSONObject]
      .get("type") shouldBe Some(JSONString("number"))
    // label: Option with default → not required
    requiredNames(address) shouldBe Set("street", "geo")
  }

  test("enums map to string schemas") {
    val schema = schemaOf("colored")
    propertyType(schema, "color") shouldBe Some(JSONString("string"))
  }

end JsonSchemaTest
