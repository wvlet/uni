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

/**
  * UTF-8 conversion with a fast path for ASCII text, the usual case for JSON and HTTP bodies. Scala
  * Native's `String(bytes, charset)` and `getBytes(charset)` allocate a coder and buffers per call;
  * an ASCII string converts byte for char without them. Other text uses the charset.
  */
private[uni] object Utf8:

  def decode(bytes: Array[Byte]): String = decode(bytes, 0, bytes.length)

  def decode(bytes: Array[Byte], offset: Int, length: Int): String =
    val end = offset + length
    var i   = offset
    while i < end && bytes(i) >= 0 do
      i += 1
    if i == end then
      val chars = new Array[Char](length)
      var j     = 0
      while j < length do
        chars(j) = bytes(offset + j).toChar
        j += 1
      String(chars)
    else
      String(bytes, offset, length, StandardCharsets.UTF_8)

  def encode(s: String): Array[Byte] =
    val n   = s.length
    val out = new Array[Byte](n)
    var i   = 0
    while i < n do
      val c = s.charAt(i)
      if c >= 0x80 then
        return s.getBytes(StandardCharsets.UTF_8)
      out(i) = c.toByte
      i += 1
    out

end Utf8
