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
package wvlet.uni

import wvlet.uni.json.JSON.JSONArray
import wvlet.uni.json.JSON.JSONBoolean
import wvlet.uni.json.JSON.JSONDouble
import wvlet.uni.json.JSON.JSONLong
import wvlet.uni.json.JSON.JSONNull
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.json.JSON.JSONString
import wvlet.uni.json.JSON.JSONValue

/**
  */
package object json:
  // Alias to encode msgpack into JSON strings with airframe-codec
  type Json = String

  implicit class RichJson(private val json: Json) extends AnyVal:
    def toJSONValue: JSONValue = JSON.parseAny(json)

  implicit class JSONValueOps(private val jsonValue: JSONValue) extends AnyVal:
    def /(name: String): Seq[JSONValue] =
      jsonValue match
        case jsonObject: JSONObject =>
          jsonObject
            .v
            .collect {
              case (key, value) if key == name =>
                value
            }
        case jsonArray: JSONArray =>
          jsonArray
            .v
            .flatMap { case value =>
              value / name
            }
        case _ =>
          Nil

    def getValue: Any =
      jsonValue match
        case _: JSONNull =>
          null
        case JSONDouble(x) =>
          x
        case JSONLong(x) =>
          x
        case JSONString(x) =>
          x
        case JSONBoolean(x) =>
          x
        case JSONArray(x) =>
          x.map(_.getValue)
        case JSONObject(x) =>
          x.map(x => (x._1, x._2.getValue)).toMap

    @deprecated(
      message = ".value will be ambiguous in Scala 3. Use getValue instead",
      since = "21.5.3"
    )
    def value: Any = getValue

    def isNull: Boolean = jsonValue.isInstanceOf[JSONNull]

    def toStringValue: String                 = jsonValue.asInstanceOf[JSONString].v
    def toDoubleValue: Double                 = jsonValue.asInstanceOf[JSONDouble].v
    def toLongValue: Long                     = jsonValue.asInstanceOf[JSONLong].v
    def toBooleanValue: Boolean               = jsonValue.asInstanceOf[JSONBoolean].v
    def toArrayValue: IndexedSeq[JSONValue]   = jsonValue.asInstanceOf[JSONArray].v
    def toObjectValue: Map[String, JSONValue] = jsonValue.asInstanceOf[JSONObject].v.toMap

    def apply(name: String): JSONValue = jsonValue.asInstanceOf[JSONObject].get(name).get
    def apply(i: Int): JSONValue       = jsonValue.asInstanceOf[JSONArray].v(i)

  end JSONValueOps

  implicit class JSONValueSeqOps(private val jsonValues: Seq[JSONValue]) extends AnyVal:
    def /(name: String): Seq[JSONValue] = jsonValues.flatMap { jsonValue =>
      jsonValue / name
    }

    def values: Seq[Any] = jsonValues.map { jsonValue =>
      jsonValue.getValue
    }

    def getValue: Any = jsonValues.head.getValue

    @deprecated(
      message = ".value will be ambiguous in Scala 3. Use getValue instead",
      since = "21.5.3"
    )
    def value: Any = jsonValues.head.getValue

    def toStringValues: Seq[String]                 = jsonValues.map(_.asInstanceOf[JSONString].v)
    def toDoubleValues: Seq[Double]                 = jsonValues.map(_.asInstanceOf[JSONDouble].v)
    def toLongValues: Seq[Long]                     = jsonValues.map(_.asInstanceOf[JSONLong].v)
    def toBooleanValues: Seq[Boolean]               = jsonValues.map(_.asInstanceOf[JSONBoolean].v)
    def toArrayValues: Seq[IndexedSeq[JSONValue]]   = jsonValues.map(_.asInstanceOf[JSONArray].v)
    def toObjectValues: Seq[Map[String, JSONValue]] = jsonValues.map(
      _.asInstanceOf[JSONObject].v.toMap
    )

    def toStringValue: String                 = jsonValues.head.asInstanceOf[JSONString].v
    def toDoubleValue: Double                 = jsonValues.head.asInstanceOf[JSONDouble].v
    def toLongValue: Long                     = jsonValues.head.asInstanceOf[JSONLong].v
    def toBooleanValue: Boolean               = jsonValues.head.asInstanceOf[JSONBoolean].v
    def toArrayValue: IndexedSeq[JSONValue]   = jsonValues.head.asInstanceOf[JSONArray].v
    def toObjectValue: Map[String, JSONValue] = jsonValues.head.asInstanceOf[JSONObject].v.toMap

    def apply(name: String): JSONValue = jsonValues.head.asInstanceOf[JSONObject].get(name).get
    def apply(i: Int): JSONValue       = jsonValues.head.asInstanceOf[JSONArray].v(i)

  end JSONValueSeqOps

end json
