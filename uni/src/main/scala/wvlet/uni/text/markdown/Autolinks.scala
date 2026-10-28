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

import scala.collection.mutable

/**
  * GitHub Flavored Markdown extended autolinks: `www.` links, `http://`, `https://`, and `ftp://`
  * URLs, and email addresses in plain text
  */
private[markdown] object Autolinks:

  def linkify(inlines: Seq[Inline]): Seq[Inline] = inlines.flatMap {
    case Inline.Text(t, src) =>
      split(t, src)
    case e: Inline.Emphasis =>
      Seq(e.copy(children = linkify(e.children)))
    case s: Inline.Strong =>
      Seq(s.copy(children = linkify(s.children)))
    case d: Inline.Strikethrough =>
      Seq(d.copy(children = linkify(d.children)))
    case other =>
      Seq(other)
  }

  private val schemes = Seq("http://", "https://", "ftp://")

  private def split(t: String, src: Source): Seq[Inline] =
    // The pieces keep their source text when it equals the text (no escapes or entities)
    def piece(start: Int, end: Int): Source =
      if src.raw == t then
        Source.unsealed(t.substring(start, end))
      else
        Source.none
    val out  = mutable.ArrayBuffer.empty[Inline]
    var last = 0
    var i    = 0
    // The end of the current whitespace-delimited token; candidates only move forward
    var tokenEnd                     = -1
    def tokenEndFrom(from: Int): Int =
      if tokenEnd < from then
        tokenEnd = from
        while tokenEnd < t.length && !Character.isWhitespace(t.charAt(tokenEnd)) &&
          t.charAt(tokenEnd) != '<'
        do
          tokenEnd += 1
      tokenEnd
    while i < t.length do
      linkAt(t, i, last, tokenEndFrom) match
        case Some((start, end, href)) =>
          if start > last then
            out += Inline.Text(t.substring(last, start), piece(last, start))
          out +=
            Inline.Link(
              MarkdownText.normalizeUri(href),
              None,
              Seq(Inline.Text(t.substring(start, end), piece(start, end))),
              piece(start, end)
            )
          last = end
          i = end
        case None =>
          i += 1
    if last == 0 then
      Seq(Inline.Text(t, src))
    else
      if last < t.length then
        out += Inline.Text(t.substring(last), piece(last, t.length))
      out.toSeq

  end split

  /**
    * Find an autolink starting at `i` (an email may start before `i`, but not before `min`)
    */
  private def linkAt(
      t: String,
      i: Int,
      min: Int,
      tokenEnd: Int => Int
  ): Option[(Int, Int, String)] =
    val c = t.charAt(i)
    if c == '@' then
      emailAt(t, i, min)
    else if (c == 'w' || c == 'h' || c == 'f') && precededByBoundary(t, i) then
      if t.startsWith("www.", i) then
        urlEnd(t, i, i, tokenEnd).map(end => (i, end, s"http://${t.substring(i, end)}"))
      else
        schemes
          .find(s => t.startsWith(s, i))
          .flatMap(s => urlEnd(t, i, i + s.length, tokenEnd))
          .map(end => (i, end, t.substring(i, end)))
    else
      None

  private def precededByBoundary(t: String, i: Int): Boolean =
    i == 0 || {
      val p = t.charAt(i - 1)
      p == ' ' || p == '\t' || p == '\n' || p == '*' || p == '_' || p == '~' || p == '('
    }

  private def isDomainChar(c: Char): Boolean =
    c < 128 && (c.isLetterOrDigit || c == '-' || c == '_' || c == '.')

  /**
    * The end of a URL whose domain starts at `domainStart`, after removing trailing punctuation
    */
  private def urlEnd(t: String, start: Int, domainStart: Int, tokenEnd: Int => Int): Option[Int] =
    var domainEnd = domainStart
    while domainEnd < t.length && isDomainChar(t.charAt(domainEnd)) do
      domainEnd += 1
    def validDomain(end: Int): Boolean =
      val segments = t.substring(domainStart, end).split("\\.", -1)
      segments.length >= 2 && segments.forall(_.nonEmpty) &&
      segments.takeRight(2).forall(!_.contains('_'))
    // Reject an invalid domain before looking at the rest of the token
    val trimmedDomainEnd = trimTrailing(t, start, domainEnd)
    if !validDomain(trimmedDomainEnd.max(domainStart)) then
      None
    else
      val end = trimTrailing(t, start, tokenEnd(domainEnd))
      Option.when(validDomain(domainEnd.min(end)))(end)

  private def trimTrailing(t: String, start: Int, initialEnd: Int): Int =
    var end      = initialEnd
    var continue = true
    var opens    = 0
    var closes   = 0
    var i        = start
    while i < end do
      if t.charAt(i) == '(' then
        opens += 1
      else if t.charAt(i) == ')' then
        closes += 1
      i += 1
    while continue && end > start do
      val last = t.charAt(end - 1)
      if "?!.,:*_~".indexOf(last) >= 0 then
        end -= 1
      else if last == ')' then
        if closes > opens then
          end -= 1
          closes -= 1
        else
          continue = false
      else if last == ';' then
        // Exclude an entity-like suffix such as `&hl;`
        var j = end - 2
        while j > start && t.charAt(j).isLetterOrDigit && t.charAt(j) < 128 do
          j -= 1
        if j >= start && t.charAt(j) == '&' && j < end - 2 then
          end = j
        else
          continue = false
      else
        continue = false
    end

  end trimTrailing

  private def isEmailLocalChar(c: Char): Boolean =
    c < 128 && (c.isLetterOrDigit || c == '.' || c == '-' || c == '_' || c == '+')

  private def emailAt(t: String, at: Int, min: Int): Option[(Int, Int, String)] =
    var start = at
    while start > min && isEmailLocalChar(t.charAt(start - 1)) do
      start -= 1
    var end = at + 1
    while end < t.length && isDomainChar(t.charAt(end)) do
      end += 1
    while end > at + 1 && t.charAt(end - 1) == '.' do
      end -= 1
    val domain = t.substring(at + 1, end)
    val valid = start < at && domain.contains('.') && !domain.endsWith("-") && !domain.endsWith("_")
    Option.when(valid) {
      val email = t.substring(start, end)
      (start, end, s"mailto:${email}")
    }

end Autolinks
