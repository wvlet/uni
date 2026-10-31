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
package wvlet.uni.cli

/**
  * A completion candidate
  *
  * @param value
  *   the text that replaces the word being completed
  * @param display
  *   how the candidate is listed when several match; defaults to `value`
  * @param complete
  *   when this is the only match, append a space after it. Use false for a directory path that the
  *   user will keep typing into.
  */
case class Candidate(value: String, display: String = "", complete: Boolean = true):
  def displayText: String =
    if display.isEmpty then
      value
    else
      display

/**
  * Candidates for replacing `buffer.substring(start, cursor)`
  */
case class Completion(start: Int, candidates: Seq[Candidate])

/**
  * Computes completions for Tab in [[LineReader]]
  */
trait Completer:
  /**
    * @param buffer
    *   the whole input, which can span multiple lines
    * @param cursor
    *   the cursor position in `buffer`
    */
  def complete(buffer: String, cursor: Int): Completion

object Completer:
  val empty: Completer = (buffer, cursor) => Completion(cursor, Seq.empty)

  /**
    * Complete the word before the cursor. A word ends at whitespace or at one of `delimiters`; a
    * leading quote (`'`, `"`, or a backquote) stays in the word, so `f` sees it and can close it.
    */
  def word(delimiters: String = "")(f: String => Seq[Candidate]): Completer =
    (buffer, cursor) =>
      val start = wordStart(buffer, cursor, delimiters)
      Completion(start, f(buffer.substring(start, cursor)))

  /**
    * Complete the word before the cursor with the given fixed words, e.g., keywords
    */
  def words(candidates: Seq[String]): Completer =
    word() { prefix =>
      candidates.filter(_.startsWith(prefix)).map(Candidate(_))
    }

  private[cli] def wordStart(buffer: String, cursor: Int, delimiters: String): Int =
    var i = cursor
    while i > 0 && !buffer.charAt(i - 1).isWhitespace &&
      delimiters.indexOf(buffer.charAt(i - 1)) < 0
    do
      i -= 1
    i

end Completer
