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
package wvlet.uni.text.markdown

import java.nio.charset.StandardCharsets

/**
  * Character classes and text transformations shared by the Markdown parsers and the HTML renderer
  */
private[markdown] object MarkdownText:

  val escapablePunctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

  def isAsciiPunctuation(c: Char): Boolean = escapablePunctuation.indexOf(c) >= 0

  def isSpaceOrTab(c: Char): Boolean = c == ' ' || c == '\t'

  /**
    * Unicode whitespace in the CommonMark sense (Zs, tab, line feed, form feed, carriage return)
    */
  def isUnicodeWhitespace(cp: Int): Boolean =
    cp == '\t' || cp == '\n' || cp == '\f' || cp == '\r' ||
      Character.getType(cp) == Character.SPACE_SEPARATOR

  /**
    * Unicode punctuation in the CommonMark 0.31 sense: P* and S* general categories
    */
  def isUnicodePunctuation(cp: Int): Boolean =
    if cp < 128 then
      isAsciiPunctuation(cp.toChar)
    else
      Character.getType(cp) match
        case Character.CONNECTOR_PUNCTUATION | Character.DASH_PUNCTUATION | Character
              .START_PUNCTUATION | Character.END_PUNCTUATION | Character.INITIAL_QUOTE_PUNCTUATION |
            Character.FINAL_QUOTE_PUNCTUATION | Character.OTHER_PUNCTUATION | Character
              .MATH_SYMBOL | Character.CURRENCY_SYMBOL | Character.MODIFIER_SYMBOL | Character
              .OTHER_SYMBOL =>
          true
        case _ =>
          false

  def escapeHtml(s: String): String =
    if s.indexOf('&') < 0 && s.indexOf('<') < 0 && s.indexOf('>') < 0 && s.indexOf('"') < 0 then
      s
    else
      val sb = StringBuilder(s.length + 16)
      s.foreach {
        case '&' =>
          sb.append("&amp;")
        case '<' =>
          sb.append("&lt;")
        case '>' =>
          sb.append("&gt;")
        case '"' =>
          sb.append("&quot;")
        case c =>
          sb.append(c)
      }
      sb.result()

  /**
    * Decode an entity or numeric character reference at `i` (pointing at `&`). Returns the decoded
    * text and the index after the reference.
    */
  def decodeEntity(s: String, i: Int): Option[(String, Int)] =
    val semi = s.indexOf(';', i + 1)
    if semi < 0 || semi - i > 40 then
      None
    else
      val body = s.substring(i + 1, semi)
      if body.startsWith("#") then
        val (digits, radix) =
          if body.length > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X') then
            (body.substring(2), 16)
          else
            (body.substring(1), 10)
        val maxLength =
          if radix == 16 then
            6
          else
            7
        if digits.isEmpty || digits.length > maxLength ||
          !digits.forall(c => Character.digit(c, radix) >= 0)
        then
          None
        else
          val cp      = Integer.parseInt(digits, radix)
          val decoded =
            if cp == 0 || cp > Character.MAX_CODE_POINT || cp >= 0xd800 && cp <= 0xdfff then
              "�"
            else
              String(Character.toChars(cp))
          Some((decoded, semi + 1))
      else if body.nonEmpty && body.forall(_.isLetterOrDigit) then
        HtmlEntities.get(body).map(v => (v, semi + 1))
      else
        None
    end if

  end decodeEntity

  /**
    * Process backslash escapes and entity references in link destinations, titles, and code block
    * info strings
    */
  def unescapeString(s: String): String =
    if s.indexOf('\\') < 0 && s.indexOf('&') < 0 then
      s
    else
      val sb = StringBuilder(s.length)
      var i  = 0
      while i < s.length do
        val c = s.charAt(i)
        if c == '\\' && i + 1 < s.length && isAsciiPunctuation(s.charAt(i + 1)) then
          sb.append(s.charAt(i + 1))
          i += 2
        else if c == '&' then
          decodeEntity(s, i) match
            case Some((text, next)) =>
              sb.append(text)
              i = next
            case None =>
              sb.append(c)
              i += 1
        else
          sb.append(c)
          i += 1
      sb.result()

  /**
    * Normalize a link label for matching reference definitions: case fold, trim, and collapse
    * internal whitespace
    */
  def normalizeLabel(label: String): String =
    label.trim.split("[ \t\r\n]+").mkString(" ").toLowerCase.toUpperCase

  private val uriSafe = ";/?:@&=+$,-_.!~*'()#"

  /**
    * Percent-encode characters that are not allowed in a URL, keeping existing `%XX` escapes
    */
  def normalizeUri(uri: String): String =
    val sb = StringBuilder(uri.length)
    var i  = 0
    while i < uri.length do
      val c = uri.charAt(i)
      if c == '%' && i + 2 < uri.length && isHex(uri.charAt(i + 1)) && isHex(uri.charAt(i + 2)) then
        sb.append(uri.substring(i, i + 3))
        i += 3
      else if c < 128 && (c.isLetterOrDigit || uriSafe.indexOf(c) >= 0) then
        sb.append(c)
        i += 1
      else
        val cp  = uri.codePointAt(i)
        val len = Character.charCount(cp)
        String(Character.toChars(cp))
          .getBytes(StandardCharsets.UTF_8)
          .foreach(b => sb.append('%').append(f"${b & 0xff}%02X"))
        i += len
    sb.result()

  private def isHex(c: Char): Boolean = Character.digit(c, 16) >= 0

end MarkdownText
