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
import wvlet.uni.text.parser.LineReader
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
    * end with a `---` (or `...`) line; otherwise the whole text is the body.
    */
  def parse(text: String): Frontmatter =
    val s =
      val t = LineReader.normalizeLineBreaks(text)
      if t.startsWith("\uFEFF") then
        t.substring(1)
      else
        t
    if !(s.startsWith("---\n") || s == "---") then
      Frontmatter(None, text)
    else
      closingLine(s) match
        case None =>
          Frontmatter(None, text)
        case Some(closeStart) =>
          // closeStart >= 3, the line break right after the opening "---"
          val yaml    = s.substring(4, closeStart + 1)
          val bodyEnd = s.indexOf('\n', closeStart + 1)
          val body    =
            if bodyEnd < 0 then
              ""
            else
              s.substring(bodyEnd + 1)
          Frontmatter(Some(YAML.parse(yaml)), body)

  /**
    * Find the offset of the line break before the closing `---` or `...` line
    */
  private def closingLine(s: String): Option[Int] =
    var i      = 3
    var result = Option.empty[Int]
    while result.isEmpty && i >= 0 && i < s.length do
      if s.charAt(i) == '\n' && (s.startsWith("---", i + 1) || s.startsWith("...", i + 1)) then
        val after = i + 4
        if after >= s.length || s.charAt(after) == '\n' then
          result = Some(i)
      if result.isEmpty then
        i = s.indexOf('\n', i + 1)
    result

end Frontmatter
