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
package wvlet.uni.ui.base

/**
  * How well a query matched a text: a higher `score` is a better match, and `positions` are the
  * indexes of the matched characters in the text, for highlighting.
  */
case class FuzzyMatch(score: Double, positions: Seq[Int])

/**
  * Subsequence ("fuzzy") matching, as in command palettes and file pickers: "nwch" matches "New
  * chat". Matching ignores case and spaces in the query. Matches score higher when they are
  * contiguous, start a word, or start the text, and lower when they are spread out.
  *
  * {{{
  *   Fuzzy.matches("tgd", "Toggle dock")   // Some(FuzzyMatch(..., Seq(0, 3, 7)))
  *   Fuzzy.matches("xyz", "Toggle dock")   // None
  * }}}
  */
object Fuzzy:

  private def isWordStart(text: String, i: Int): Boolean =
    i == 0 || {
      val prev = text.charAt(i - 1)
      val c    = text.charAt(i)
      !prev.isLetterOrDigit || (prev.isLower && c.isUpper)
    }

  /** None when `query` is not a subsequence of `text`; an empty query matches everything. */
  def matches(query: String, text: String): Option[FuzzyMatch] =
    // Lowercase char by char: String.toLowerCase can lengthen text ("İ" becomes two chars), which
    // would shift match positions away from the original text
    val q = query.filterNot(_.isWhitespace).map(_.toLower)
    if q.isEmpty then
      Some(FuzzyMatch(0.0, Seq.empty))
    else
      val lower = text.map(_.toLower)
      // A contiguous match wins: pick the best placed occurrence
      val substring = Iterator
        .iterate(lower.indexOf(q))(i => lower.indexOf(q, i + 1))
        .takeWhile(_ >= 0)
        .maxByOption(i =>
          if i == 0 then
            2
          else if isWordStart(text, i) then
            1
          else
            0
        )
      substring match
        case Some(start) =>
          val bonus =
            if start == 0 then
              3.0
            else if isWordStart(text, start) then
              2.0
            else
              1.0
          Some(
            FuzzyMatch(10.0 * q.length + bonus - text.length * 0.01, start until start + q.length)
          )
        case None =>
          // Jumping to word starts can skip letters a later query character needs; fall back to
          // the plain leftmost match then
          subsequence(q, text, lower, preferWordStarts = true).orElse(
            subsequence(q, text, lower, preferWordStarts = false)
          )
    end if

  end matches

  // Each query character takes its next occurrence. With `preferWordStarts`, a character that
  // doesn't continue the previous match takes the next word start that matches instead, if any.
  private def subsequence(
      q: String,
      text: String,
      lower: String,
      preferWordStarts: Boolean
  ): Option[FuzzyMatch] =
    val positions = Seq.newBuilder[Int]
    var from      = 0
    var score     = 0.0
    var last      = -2
    var k         = 0
    while k < q.length && from >= 0 do
      val c     = q.charAt(k)
      val plain = lower.indexOf(c, from)
      if plain < 0 then
        from = -1
      else
        val i =
          if preferWordStarts && plain != last + 1 then
            (plain until lower.length)
              .find(j => lower.charAt(j) == c && isWordStart(text, j))
              .getOrElse(plain)
          else
            plain
        score += (
          if i == last + 1 then
            3.0
          else if isWordStart(text, i) then
            2.0
          else
            1.0
        )
        positions += i
        last = i
        from = i + 1
        k += 1
    if k == q.length then
      Some(FuzzyMatch(score - text.length * 0.01, positions.result()))
    else
      None

  end subsequence

end Fuzzy
