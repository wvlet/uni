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
package wvlet.uni.weaver.codec

import wvlet.uni.json.JSON
import wvlet.uni.json.Json
import wvlet.uni.json.JSON.*
import wvlet.uni.msgpack.spi.*
import wvlet.uni.msgpack.spi.Value.TimestampValue
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext

/**
  * Codec for JSON values. Internally, JSON String is converted to MessagePack
  */
object JSONWeaver extends Weaver[String]:
  override def pack(p: Packer, json: String, config: WeaverConfig): Unit =
    val msgpack = MessagePack.fromJSON(json)
    p.writePayload(msgpack)

  private def toMsgPack(jsonValue: JSONValue, config: WeaverConfig): Array[Byte] =
    val packer = MessagePack.newPacker()
    packJsonValue(packer, jsonValue, config)
    packer.toByteArray

  def packJsonValue(p: Packer, v: JSONValue, config: WeaverConfig): Unit =
    import wvlet.uni.json.JSON.*
    v match
      case JSONObject(map) =>
        p.packMapHeader(map.size)
        for (k: String, v: JSONValue) <- map do
          p.packString(k)
          packJsonValue(p, v, config)
      case JSONArray(arr) =>
        val len = arr.size
        p.packArrayHeader(len)
        arr.foreach {
          packJsonValue(p, _, config)
        }
      case JSONString(s) =>
        p.packString(s)
      case _: JSONNull =>
        p.packNil
      case JSONBoolean(v) =>
        p.packBoolean(v)
      case JSONDouble(v) =>
        p.packDouble(v)
      case JSONLong(l) =>
        p.packLong(l)
      case null =>
        throw new IllegalArgumentException(s"Unexpected json input: null")

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    val json = u.unpackValue.toJson
    context.setString(json)

end JSONWeaver

object RawJSONWeaver extends Weaver[Json]:
  override def pack(p: Packer, v: Json, config: WeaverConfig): Unit = JSONWeaver.pack(p, v, config)
  override def unpack(u: Unpacker, context: WeaverContext): Unit    = JSONWeaver.unpack(u, context)

/**
  * Codec for JSONValue
  */
object JSONValueWeaver extends Weaver[JSONValue]:
  override def pack(p: Packer, v: JSONValue, config: WeaverConfig): Unit = JSONWeaver.packJsonValue(
    p,
    v,
    config
  )

  def unpackJson(u: Unpacker): JSONValue =
    u.getNextValueType match
      case ValueType.NIL =>
        u.unpackNil
        JSONNull()
      case ValueType.STRING =>
        JSONString(u.unpackString)
      case ValueType.FLOAT =>
        JSONDouble(u.unpackDouble)
      case ValueType.INTEGER =>
        JSONLong(u.unpackLong)
      case ValueType.BOOLEAN =>
        JSONBoolean(u.unpackBoolean)
      case ValueType.EXTENSION =>
        u.unpackValue match
          case v: TimestampValue =>
            JSONString(v.toRawString)
          case other =>
            JSONString(other.toString)
      case ValueType.BINARY =>
        JSONString(u.unpackValue.toJson)
      case ValueType.ARRAY =>
        val len = u.unpackArrayHeader
        val b   = IndexedSeq.newBuilder[JSONValue]
        for i <- 0 until len do
          b += unpackJson(u)
        JSONArray(b.result())
      case ValueType.MAP =>
        val len = u.unpackMapHeader
        val b   = Seq.newBuilder[(String, JSONValue)]
        for i <- 0 until len
        yield
          val key = u.unpackString
          b += key -> unpackJson(u)
        JSONObject(b.result())

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    val jsonValue = unpackJson(u)
    context.setObject(jsonValue)

end JSONValueWeaver
