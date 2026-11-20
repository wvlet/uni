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
package wvlet.uni.util

/**
  * UTF-8 conversion for request and response bodies, JSON, and MessagePack strings, the fastest way
  * on each platform. The JVM keeps `String(bytes, UTF_8)` and `getBytes(UTF_8)`, which HotSpot
  * intrinsifies (compact strings and a vectorized ASCII scan). Scala Native's versions allocate a
  * coder and buffers per call, so there ASCII text converts byte for char without them, and other
  * text uses the charset.
  */
private[uni] object Utf8:

  /**
    * Whether hand-written ASCII loops beat the standard library on this platform. False on the JVM,
    * where they would replace intrinsics; callers with an ASCII fast path of their own check it.
    */
  inline def manualAscii: Boolean = Utf8Compat.manualAscii

  /**
    * Whether writing a number's digits by hand beats `StringBuilder.append(Long)`. True only on
    * Scala Native, where that append builds a String; Scala.js emulates Long division in software.
    */
  inline def manualDigits: Boolean = Utf8Compat.manualDigits

  def decode(bytes: Array[Byte]): String = Utf8Compat.decode(bytes, 0, bytes.length)

  def decode(bytes: Array[Byte], offset: Int, length: Int): String = Utf8Compat.decode(
    bytes,
    offset,
    length
  )

  def encode(s: String): Array[Byte] = Utf8Compat.encode(s)

  // The ASCII paths the Native and Scala.js Utf8Compat share

  private[util] def isAscii(bytes: Array[Byte], offset: Int, length: Int): Boolean =
    val end = offset + length
    var i   = offset
    while i < end && bytes(i) >= 0 do
      i += 1
    i == end

  /** `s` as UTF-8: byte for char when it is ASCII, through the charset otherwise */
  private[util] def encodeAsciiFirst(s: String): Array[Byte] =
    val n = s.length
    var i = 0
    while i < n && s.charAt(i) < 0x80 do
      i += 1
    if i < n then
      s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    else
      val out = new Array[Byte](n)
      var j   = 0
      while j < n do
        out(j) = s.charAt(j).toByte
        j += 1
      out

end Utf8
