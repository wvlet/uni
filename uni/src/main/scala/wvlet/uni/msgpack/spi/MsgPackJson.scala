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
package wvlet.uni.msgpack.spi

import wvlet.uni.util.Utf8

import java.util.Base64

/**
  * Writes the next MessagePack value of an [[Unpacker]] as JSON text, reading it once. The output
  * equals `unpacker.unpackValue.toJson`, without building the [[Value]] tree and a string per node,
  * which is how `Weaver.toJson` (every RPC result) used to render.
  *
  * One difference: a map with duplicate keys keeps every entry here, where the `Value` tree keeps
  * only the last value at the first key's position. Weaver never packs duplicate keys, and JSON
  * parsers read both forms as the last value.
  */
private[uni] object MsgPackJson:

  /**
    * @param sizeHint
    *   the expected JSON length; JSON text runs about as long as its MessagePack form, so callers
    *   holding the bytes pass a little more than their length to avoid regrowing the builder
    */
  def toJson(u: Unpacker, sizeHint: Int = 64): String =
    val sb = StringBuilder(sizeHint)
    write(u, sb, Scratch())
    sb.result()

  /**
    * The next value as UTF-8 JSON bytes, rendered into `sb` (cleared first; callers reuse one).
    * ASCII JSON, the usual case, turns into bytes straight from the builder where hand-written
    * loops pay off ([[Utf8.manualAscii]]); otherwise, and on the JVM, through `getBytes`.
    */
  def toJsonBytes(u: Unpacker, sb: StringBuilder): Array[Byte] =
    sb.clear()
    write(u, sb, Scratch())
    val n = sb.length
    var i = 0
    while Utf8.manualAscii && i < n && sb.charAt(i) < 0x80 do
      i += 1
    if Utf8.manualAscii && i == n then
      val out = new Array[Byte](n)
      var j   = 0
      while j < n do
        out(j) = sb.charAt(j).toByte
        j += 1
      out
    else
      sb.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8)

  // One byte buffer per document, reused for every string's bytes; allocated on first use
  private final class Scratch:
    var bytes: Array[Byte] = null

  private def write(u: Unpacker, sb: StringBuilder, scratch: Scratch): Unit =
    val format = u.getNextFormat
    if format == MessageFormat.NEVER_USED then
      throw MessageException(ErrorCode.NEVER_USED_FORMAT, "Found 0xC1 (NEVER_USED) byte")
    format.valueType match
      case ValueType.NIL =>
        u.unpackNil
        sb.append("null")
      case ValueType.BOOLEAN =>
        sb.append(
          if u.unpackBoolean then
            "true"
          else
            "false"
        )
      case ValueType.INTEGER =>
        if format == MessageFormat.UINT64 then
          sb.append(u.unpackBigInteger.toString)
        else if Utf8.manualDigits then
          appendLong(sb, u.unpackLong)
        else
          sb.append(u.unpackLong)
      case ValueType.FLOAT =>
        val v = u.unpackDouble
        if v.isNaN || v.isInfinite then
          sb.append("null")
        else
          sb.append(v.toString)
      case ValueType.STRING =>
        if Utf8.manualAscii then
          writeString(u, sb, scratch)
        else
          Value.appendJsonString(sb, u.unpackString)
      case ValueType.BINARY =>
        val bytes = u.readPayload(u.unpackBinaryHeader)
        Value.appendJsonString(sb, Base64.getEncoder.encodeToString(bytes))
      case ValueType.EXTENSION =>
        // Timestamps and other extensions are rare; render them as their Value does
        sb.append(u.unpackExtValue(u.unpackExtTypeHeader).toJson)
      case ValueType.ARRAY =>
        val n = u.unpackArrayHeader
        sb.append('[')
        var i = 0
        while i < n do
          if i > 0 then
            sb.append(',')
          write(u, sb, scratch)
          i += 1
        sb.append(']')
      case ValueType.MAP =>
        val n = u.unpackMapHeader
        sb.append('{')
        var i = 0
        while i < n do
          if i > 0 then
            sb.append(',')
          writeKey(u, sb, scratch)
          sb.append(':')
          write(u, sb, scratch)
          i += 1
        sb.append('}')
    end match

  end write

  // A key is quoted from its unquoted form, as MapValue.toJson does: a string as-is, any other
  // value as its JSON text
  private def writeKey(u: Unpacker, sb: StringBuilder, scratch: Scratch): Unit =
    if u.getNextFormat.valueType == ValueType.STRING then
      if Utf8.manualAscii then
        writeString(u, sb, scratch)
      else
        Value.appendJsonString(sb, u.unpackString)
    else
      Value.appendJsonString(sb, u.unpackValue.toUnquotedString)

  /**
    * A string value as a JSON string. ASCII text is escaped straight from its bytes, without a
    * `String` in between; other text is decoded and escaped by [[Value.appendJsonString]], so both
    * give what `StringValue.toJson` gives.
    */
  private def writeString(u: Unpacker, sb: StringBuilder, scratch: Scratch): Unit =
    val len = u.unpackRawStringHeader
    if scratch.bytes == null || scratch.bytes.length < len then
      val current =
        if scratch.bytes == null then
          32
        else
          scratch.bytes.length
      scratch.bytes = new Array[Byte](math.max(len, current * 2))
    val bytes = scratch.bytes
    u.readPayload(bytes, 0, len)
    var i = 0
    while i < len && bytes(i) >= 0 do
      i += 1
    if i < len then
      Value.appendJsonString(sb, Utf8.decode(bytes, 0, len))
    else
      sb.append('"')
      i = 0
      while i < len do
        val ch = bytes(i).toChar
        if ch < 0x20 then
          ch match
            case '\n' =>
              sb.append("\\n")
            case '\r' =>
              sb.append("\\r")
            case '\t' =>
              sb.append("\\t")
            case '\f' =>
              sb.append("\\f")
            case '\b' =>
              sb.append("\\b")
            case _ =>
              Value.escapeChar(sb, ch)
        else if ch == '\\' then
          sb.append("\\\\")
        else if ch == '"' then
          sb.append("\\\"")
        else
          sb.append(ch)
        i += 1
      sb.append('"')

  end writeString

  // The digits of v, appended without the String that StringBuilder.append(Long) builds on Native
  private def appendLong(sb: StringBuilder, v: Long): Unit =
    if v == Long.MinValue then
      sb.append("-9223372036854775808")
    else
      var x = v
      if x < 0 then
        sb.append('-')
        x = -x
      var p = 1L
      while p <= x / 10 do
        p *= 10
      while p > 0 do
        sb.append(('0' + (x / p).toInt).toChar)
        x %= p
        p /= 10

end MsgPackJson
