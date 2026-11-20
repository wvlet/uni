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

import java.nio.charset.StandardCharsets

// Scala Native's charset conversions allocate a coder and buffers per call: convert ASCII directly
private[util] object Utf8Compat:
  inline def manualAscii: Boolean = true

  def decode(bytes: Array[Byte], offset: Int, length: Int): String =
    if isAscii(bytes, offset, length) then
      latin1(bytes, offset, length)
    else
      String(bytes, offset, length, StandardCharsets.UTF_8)

  // The hibyte constructor fills the string straight from the bytes, each byte one char
  @annotation.nowarn("cat=deprecation")
  private def latin1(bytes: Array[Byte], offset: Int, length: Int): String = String(
    bytes,
    0,
    offset,
    length
  )

  private def isAscii(bytes: Array[Byte], offset: Int, length: Int): Boolean =
    val end = offset + length
    var i   = offset
    while i < end && bytes(i) >= 0 do
      i += 1
    i == end

  def encode(s: String): Array[Byte] =
    val n = s.length
    var i = 0
    while i < n && s.charAt(i) < 0x80 do
      i += 1
    if i < n then
      s.getBytes(StandardCharsets.UTF_8)
    else
      val out = new Array[Byte](n)
      var j   = 0
      while j < n do
        out(j) = s.charAt(j).toByte
        j += 1
      out

end Utf8Compat
