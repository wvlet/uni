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

// Scala.js implements the charsets in Scala, with a coder per call: convert ASCII directly
private[util] object Utf8Compat:
  inline def manualAscii: Boolean = true

  def decode(bytes: Array[Byte], offset: Int, length: Int): String =
    val end = offset + length
    var i   = offset
    while i < end && bytes(i) >= 0 do
      i += 1
    if i < end then
      String(bytes, offset, length, StandardCharsets.UTF_8)
    else
      // No hibyte String constructor on Scala.js: copy the chars
      val chars = new Array[Char](length)
      var j     = 0
      while j < length do
        chars(j) = bytes(offset + j).toChar
        j += 1
      String(chars)

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
