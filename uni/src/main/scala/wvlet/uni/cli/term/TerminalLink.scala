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
package wvlet.uni.cli.term

/**
  * Which OSC 8 hyperlinks a terminal app may open. Program output chooses the URI, so an app opens
  * only links whose scheme is in an allowlist: by default `http`, `https`, and `mailto`. Other
  * schemes, such as `javascript:`, `file:`, or an app's own handler, are not opened.
  *
  * {{{
  *   TerminalLink.isAllowed("https://wvlet.org/uni") // true
  *   TerminalLink.isAllowed("file:///etc/passwd")    // false
  * }}}
  */
object TerminalLink:
  /**
    * The schemes opened by default
    */
  val defaultSchemes: Set[String] = Set("http", "https", "mailto")

  /**
    * The URI's scheme in lower case, or None when the URI does not start with one (RFC 3986: a
    * letter, then letters, digits, `+`, `-`, or `.`, then `:`)
    */
  def scheme(uri: String): Option[String] =
    val colon = uri.indexOf(':')
    if colon <= 0 then
      None
    else
      val s     = uri.substring(0, colon)
      val valid =
        s.charAt(0).isLetter && s.forall(c => c < 0x80 && (c.isLetterOrDigit || "+-.".contains(c)))
      if valid then
        Some(s.toLowerCase)
      else
        None

  /**
    * True when the URI's scheme is one of `schemes`. URIs with control characters or spaces are
    * never allowed.
    */
  def isAllowed(uri: String, schemes: Set[String] = defaultSchemes): Boolean =
    !uri.exists(c => c <= ' ' || c == '\u007f') && scheme(uri).exists(schemes.contains)

end TerminalLink
