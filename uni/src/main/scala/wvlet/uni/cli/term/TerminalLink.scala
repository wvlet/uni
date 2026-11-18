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
  * [[findUrls]] finds plain `http` and `https` URLs in text without OSC 8 markup, so a terminal app
  * can make them clickable too.
  *
  * {{{
  *   TerminalLink.isAllowed("https://wvlet.org/uni") // true
  *   TerminalLink.isAllowed("file:///etc/passwd")    // false
  *   TerminalLink.findUrls("see https://wvlet.org/uni.")
  *   // Seq(TerminalLink.UrlMatch(start = 4, end = 25, uri = "https://wvlet.org/uni"))
  * }}}
  */
object TerminalLink:
  /**
    * A URL found in text: the characters from `start` (inclusive) to `end` (exclusive)
    */
  case class UrlMatch(start: Int, end: Int, uri: String)

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

  /**
    * Find plain `http://` and `https://` URLs in text. A URL ends at whitespace, a non-ASCII
    * character, or one of `<>"` and backtick. Trailing sentence punctuation (`.,;:!?`), quotes, and
    * closing brackets without a matching opening bracket in the URL are left out, so
    * `(see https://wvlet.org/uni).` finds `https://wvlet.org/uni`.
    */
  def findUrls(text: String): Seq[UrlMatch] =
    val found = Seq.newBuilder[UrlMatch]
    var i     = 0
    while i < text.length do
      val start = schemeStart(text, i)
      if start < 0 then
        i = text.length
      else
        var end = start
        while end < text.length && isUrlChar(text.charAt(end)) do
          end += 1
        end = trimEnd(text, start, end)
        val afterScheme = text.indexOf("//", start) + 2
        if end > afterScheme then
          found += UrlMatch(start, end, text.substring(start, end))
          i = end
        else
          i = afterScheme
    found.result()

  // The index of the next `http://` or `https://` (in any case) at or after `from` that does not
  // continue a word, or -1
  private def schemeStart(text: String, from: Int): Int =
    var i      = from
    var result = -1
    while result < 0 && i < text.length do
      val c = text.charAt(i)
      if (c == 'h' || c == 'H') &&
        (i == 0 || !text.charAt(i - 1).isLetterOrDigit) && (
          text.regionMatches(true, i, "https://", 0, 8) ||
            text.regionMatches(true, i, "http://", 0, 7)
        )
      then
        result = i
      i += 1
    result

  private def isUrlChar(c: Char): Boolean =
    c > ' ' && c < 0x7f && c != '<' && c != '>' && c != '"' && c != '`'

  // Drop trailing punctuation and closing brackets that do not close a bracket in the URL
  private def trimEnd(text: String, start: Int, end: Int): Int =
    var e       = end
    var trimmed = true
    while trimmed && e > start do
      trimmed = false
      val c = text.charAt(e - 1)
      if ".,;:!?'\"*".contains(c) then
        e -= 1
        trimmed = true
      else
        val open =
          c match
            case ')' =>
              '('
            case ']' =>
              '['
            case '}' =>
              '{'
            case _ =>
              0.toChar
        if open != 0 then
          val url = text.substring(start, e)
          if url.count(_ == open) < url.count(_ == c) then
            e -= 1
            trimmed = true
    e

end TerminalLink
