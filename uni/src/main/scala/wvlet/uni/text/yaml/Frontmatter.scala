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
package wvlet.uni.text.yaml

import wvlet.uni.json.JSON.*
import wvlet.uni.weaver.Weaver

/**
  * A YAML header delimited by `---` lines at the beginning of a text, as used in Markdown files and
  * agent skills:
  * {{{
  *   ---
  *   name: my-skill
  *   description: Summarize a PR
  *   ---
  *   # Body
  * }}}
  *
  * @param data
  *   the parsed header, or None when the text has no frontmatter
  * @param body
  *   the text after the closing `---` line (the whole text when there is no frontmatter)
  */
case class Frontmatter(data: Option[JSONValue], body: String):

  /**
    * Decode the header into `A`. A missing or empty header decodes as an empty mapping, so fields
    * take their default values.
    */
  def as[A](using weaver: Weaver[A]): A =
    val v =
      data match
        case None | Some(JSONNull()) =>
          JSONObject.empty
        case Some(d) =>
          d
    weaver.fromJSONValue(v)

object Frontmatter:

  /**
    * Split the frontmatter from the text. The header must start at the first line with `---` and
    * end with a `---` (or `...`) line; otherwise the whole text is the body. The body keeps the
    * original line breaks.
    */
  def parse(text: String): Frontmatter =
    val start =
      if text.startsWith("\uFEFF") then
        1
      else
        0
    val firstEnd = lineEnd(text, start)
    if firstEnd >= text.length || !isMarker(text, start, firstEnd, "---") then
      Frontmatter(None, text)
    else
      closingLine(text, firstEnd + 1) match
        case None =>
          Frontmatter(None, text)
        case Some((closeStart, closeEnd)) =>
          // Keep the opening "---" (a YAML document start) so that error lines match the text
          val header = YAML.parse(text.substring(0, closeStart))
          val body   =
            if closeEnd < text.length then
              text.substring(closeEnd + 1)
            else
              ""
          Frontmatter(Some(header), body)

  private def lineEnd(text: String, from: Int): Int =
    val i = text.indexOf('\n', from)
    if i < 0 then
      text.length
    else
      i

  /**
    * Is the line [from, end) the marker, ignoring trailing spaces, tabs, and '\r'?
    */
  private def isMarker(text: String, from: Int, end: Int, marker: String): Boolean =
    var e = end
    while e > from && " \t\r".indexOf(text.charAt(e - 1)) >= 0 do
      e -= 1
    e - from == marker.length && text.startsWith(marker, from)

  /**
    * Find the (start, end) offsets of the closing `---` or `...` line
    */
  private def closingLine(text: String, from: Int): Option[(Int, Int)] =
    var lineStart = from
    var result    = Option.empty[(Int, Int)]
    while result.isEmpty && lineStart < text.length do
      val end = lineEnd(text, lineStart)
      if isMarker(text, lineStart, end, "---") || isMarker(text, lineStart, end, "...") then
        result = Some((lineStart, end))
      lineStart = end + 1
    result

end Frontmatter
