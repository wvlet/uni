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
  * Cross-platform URL encoder/decoder implementation following RFC 3986
  */
object URLEncoder:

  private val hexChars = "0123456789ABCDEF".toCharArray

  /**
    * URL-encode a string using UTF-8 encoding. Unreserved characters (A-Z, a-z, 0-9, -, _, ., ~)
    * are not encoded as per RFC 3986.
    */
  def encode(s: String): String =
    if s.isEmpty then
      return ""

    val bytes = s.getBytes("UTF-8")
    val sb    = StringBuilder()

    for b <- bytes do
      val c = b & 0xff
      if isUnreserved(c) then
        sb.append(c.toChar)
      else
        sb.append('%')
        sb.append(hexChars(c >> 4))
        sb.append(hexChars(c & 0x0f))

    sb.toString

  /**
    * URL-decode a string using UTF-8 encoding.
    */
  def decode(s: String): String =
    if s.isEmpty then
      return ""

    val bytes = Array.newBuilder[Byte]
    var i     = 0
    val len   = s.length

    while i < len do
      val c = s.charAt(i)
      if c == '%' && i + 2 < len then
        val hex = s.substring(i + 1, i + 3)
        try
          bytes += Integer.parseInt(hex, 16).toByte
          i += 3
        catch
          case _: NumberFormatException =>
            bytes += c.toByte
            i += 1
      else if c == '+' then
        bytes += ' '.toByte
        i += 1
      else
        // Append UTF-8 bytes of the code point, keeping a surrogate pair (e.g., an emoji) together
        val cp = s.codePointAt(i)
        bytes ++= String(Character.toChars(cp)).getBytes("UTF-8")
        i += Character.charCount(cp)

    String(bytes.result(), "UTF-8")

  /**
    * Check if a character is an unreserved character per RFC 3986
    */
  private def isUnreserved(c: Int): Boolean =
    (c >= 'A' && c <= 'Z') ||
      (c >= 'a' && c <= 'z') ||
      (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~'

end URLEncoder
