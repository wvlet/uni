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
  * Writes MessagePack values as UTF-8 JSON bytes into a reusable buffer: the bytes of
  * `MsgPackJson.toJson(u).getBytes(UTF_8)`, without the char builder in between. For Scala Native,
  * where a `StringBuilder` costs a call and a bounds check per char; the JVM renders through
  * `MsgPackJson` instead. ASCII strings, the usual case, are escaped straight from their
  * MessagePack bytes; other text goes through [[Value.appendJsonString]], so the output is the
  * same.
  */
private[uni] final class JsonByteWriter:
  private var out: Array[Byte]     = new Array[Byte](256)
  private var pos                  = 0
  private var scratch: Array[Byte] = new Array[Byte](64)

  /** The bytes kept between uses, so a caller can drop a writer that grew too large */
  def capacity: Int = out.length

  /** The next value of `u` as JSON bytes */
  def toJsonBytes(u: Unpacker): Array[Byte] =
    pos = 0
    write(u)
    java.util.Arrays.copyOf(out, pos)

  private def ensure(n: Int): Unit =
    if pos + n > out.length then
      out = java.util.Arrays.copyOf(out, math.max(pos + n, out.length * 2))

  private def byte(b: Int): Unit =
    ensure(1)
    out(pos) = b.toByte
    pos += 1

  /** `s`, which holds only ASCII */
  private def ascii(s: String): Unit =
    val n = s.length
    ensure(n)
    var i = 0
    while i < n do
      out(pos + i) = s.charAt(i).toByte
      i += 1
    pos += n

  /** Any text, UTF-8 encoded */
  private def text(s: String): Unit =
    val b = Utf8.encode(s)
    ensure(b.length)
    System.arraycopy(b, 0, out, pos, b.length)
    pos += b.length

  /** `s` as a JSON string, escaped as [[Value.appendJsonString]] does */
  private def jsonString(s: String): Unit =
    val sb = StringBuilder(s.length + 2)
    Value.appendJsonString(sb, s)
    text(sb.toString)

  private def long(v: Long): Unit =
    if v == Long.MinValue then
      ascii("-9223372036854775808")
    else
      var x = v
      if x < 0 then
        byte('-')
        x = -x
      var digits = 1
      var p      = 10L
      while digits < 19 && x >= p do
        digits += 1
        p *= 10
      ensure(digits)
      var i = pos + digits - 1
      while i >= pos do
        out(i) = ('0' + (x % 10).toInt).toByte
        x /= 10
        i -= 1
      pos += digits

  private def write(u: Unpacker): Unit =
    val format = u.getNextFormat
    if format == MessageFormat.NEVER_USED then
      throw MessageException(ErrorCode.NEVER_USED_FORMAT, "Found 0xC1 (NEVER_USED) byte")
    format.valueType match
      case ValueType.NIL =>
        u.unpackNil
        ascii("null")
      case ValueType.BOOLEAN =>
        ascii(
          if u.unpackBoolean then
            "true"
          else
            "false"
        )
      case ValueType.INTEGER =>
        if format == MessageFormat.UINT64 then
          ascii(u.unpackBigInteger.toString)
        else
          long(u.unpackLong)
      case ValueType.FLOAT =>
        val v = u.unpackDouble
        if v.isNaN || v.isInfinite then
          ascii("null")
        else
          ascii(v.toString)
      case ValueType.STRING =>
        string(u)
      case ValueType.BINARY =>
        val bytes = u.readPayload(u.unpackBinaryHeader)
        // Base64 needs no escaping
        byte('"')
        ascii(Base64.getEncoder.encodeToString(bytes))
        byte('"')
      case ValueType.EXTENSION =>
        text(u.unpackExtValue(u.unpackExtTypeHeader).toJson)
      case ValueType.ARRAY =>
        val n = u.unpackArrayHeader
        byte('[')
        var i = 0
        while i < n do
          if i > 0 then
            byte(',')
          write(u)
          i += 1
        byte(']')
      case ValueType.MAP =>
        val n = u.unpackMapHeader
        byte('{')
        var i = 0
        while i < n do
          if i > 0 then
            byte(',')
          // A key is quoted from its unquoted form, as MapValue.toJson does
          if u.getNextFormat.valueType == ValueType.STRING then
            string(u)
          else
            jsonString(u.unpackValue.toUnquotedString)
          byte(':')
          write(u)
          i += 1
        byte('}')
    end match

  end write

  private def string(u: Unpacker): Unit =
    val len = u.unpackRawStringHeader
    // Read the payload to where the quoted string goes: an ASCII string with nothing to escape,
    // the usual case, is then written with one copy and no per-byte stores
    ensure(len + 2)
    val start = pos + 1
    u.readPayload(out, start, len)
    var plain = 0
    while plain < len && {
        val b = out(start + plain)
        // Bytes >= 0x80 are negative, so this also stops at non-ASCII
        b >= 0x20 && b != '"' && b != '\\'
      }
    do
      plain += 1
    if plain == len then
      out(pos) = '"'
      out(start + len) = '"'
      pos = start + len + 1
    else
      if scratch.length < len then
        scratch = new Array[Byte](math.max(len, scratch.length * 2))
      System.arraycopy(out, start, scratch, 0, len)
      escapedString(scratch, len)

  /** The string in `bytes(0 until len)`, which needs escaping or is not ASCII */
  private def escapedString(bytes: Array[Byte], len: Int): Unit =
    var i = 0
    while i < len && bytes(i) >= 0 do
      i += 1
    if i < len then
      jsonString(Utf8.decode(bytes, 0, len))
    else
      // At most 6 output bytes per input byte (\u00XX)
      ensure(len * 6 + 2)
      out(pos) = '"'
      pos += 1
      i = 0
      while i < len do
        val b = bytes(i)
        if b < 0x20 then
          if b == '\n' then
            escape('n')
          else if b == '\r' then
            escape('r')
          else if b == '\t' then
            escape('t')
          else if b == '\f' then
            escape('f')
          else if b == '\b' then
            escape('b')
          else
            out(pos) = '\\'
            out(pos + 1) = 'u'
            out(pos + 2) = '0'
            out(pos + 3) = '0'
            out(pos + 4) = JsonByteWriter.Hex((b >> 4) & 0x0f)
            out(pos + 5) = JsonByteWriter.Hex(b & 0x0f)
            pos += 6
        else if b == '\\' || b == '"' then
          escape(b.toChar)
        else
          out(pos) = b
          pos += 1
        i += 1
      out(pos) = '"'
      pos += 1
    end if

  end escapedString

  private inline def escape(c: Char): Unit =
    out(pos) = '\\'
    out(pos + 1) = c.toByte
    pos += 2

end JsonByteWriter

private[uni] object JsonByteWriter:
  // Lower-case, as Value.escapeChar writes
  private val Hex: Array[Byte] = "0123456789abcdef".getBytes("US-ASCII")
