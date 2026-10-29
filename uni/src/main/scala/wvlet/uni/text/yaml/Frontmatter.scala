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
  * Change the header with [[update]] (or `copy`) and write the text back with [[toText]]. Only the
  * changed parts of the header are rewritten; its comments and formatting stay.
  *
  * @param data
  *   the parsed header, or None when the text has no frontmatter
  * @param body
  *   the text after the closing `---` line (the whole text when there is no frontmatter)
  */
case class Frontmatter(
    data: Option[JSONValue],
    body: String,
    source: FrontmatterSource = FrontmatterSource.none
):

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

  def update(data: JSONValue): Frontmatter = copy(data = Some(data))

  /**
    * Write the header and the body back. An unchanged header prints as it was read.
    */
  def toText: String = headerText + body

  /**
    * The header with its `---` lines, or an empty string without one
    */
  private[text] def headerText: String = source.headerText(data)

end Frontmatter

/**
  * The header text a [[Frontmatter]] was read from. It does not take part in equality.
  */
final class FrontmatterSource private[yaml] (
    private[yaml] val header: YAMLDocument | Null,
    private[yaml] val closing: String,
    private[text] val parsed: Option[JSONValue]
):
  override def equals(other: Any): Boolean = other.isInstanceOf[FrontmatterSource]
  override def hashCode: Int               = 0
  override def toString: String            = "FrontmatterSource"

  /**
    * The length of the header text as read
    */
  private[text] def length: Int =
    if header == null then
      0
    else
      header.source.raw.length + closing.length

  private[text] def headerText(data: Option[JSONValue], lb: String = "\n"): String =
    (header, data) match
      case (null, None) =>
        ""
      case (null, Some(d)) =>
        FrontmatterSource.newHeader(d, lb)
      case (h: YAMLDocument, Some(d)) =>
        val yaml =
          if parsed.contains(d) then
            h.source.raw
          else
            val lb =
              if h.source.raw.contains("\r\n") then
                "\r\n"
              else
                "\n"
            val text = h.update(d).toYaml
            if text.endsWith("\n") then
              text
            else
              text + lb
        yaml + closing
      case (_, None) =>
        ""

end FrontmatterSource

object FrontmatterSource:
  val none: FrontmatterSource = FrontmatterSource(null, "", None)

  /**
    * Read the header text, which starts with the opening `---` line, and keep the closing line
    */
  private[text] def of(header: String, closing: String): FrontmatterSource =
    val (doc, data) = YAML.parseDocumentWithValue(header)
    FrontmatterSource(doc, closing, Some(data))

  private[text] def newHeader(data: JSONValue, lb: String): String =
    val yaml =
      data match
        case JSONObject(entries) if entries.isEmpty =>
          ""
        case JSONNull() =>
          ""
        case d =>
          val text = YAMLDocument(YAMLNode.fromJSON(d)).toYaml.replace("\n", lb)
          if text.endsWith("\n") then
            text
          else
            text + lb
    s"---${lb}${yaml}---${lb}"

object Frontmatter:

  /**
    * Split the frontmatter from the text. The header must start at the first line with `---` and
    * end with a `---` (or `...`) line; otherwise the whole text is the body. The body keeps the
    * original line breaks.
    */
  def parse(text: String): Frontmatter =
    split(text) match
      case None =>
        Frontmatter(None, text)
      case Some((closeStart, bodyStart)) =>
        // Keep the opening "---" (a YAML document start) so that error lines match the text
        val source = FrontmatterSource.of(
          text.substring(0, closeStart),
          text.substring(closeStart, bodyStart)
        )
        Frontmatter(source.parsed, text.substring(bodyStart), source)

  /**
    * Find the frontmatter without parsing it: the start offsets of the closing marker line and of
    * the body. The header text is `text.substring(0, closingLineStart)`.
    */
  private[text] def split(text: String): Option[(Int, Int)] =
    val start =
      if text.startsWith("\uFEFF") then
        1
      else
        0
    val firstEnd = lineEnd(text, start)
    if firstEnd >= text.length || !isMarker(text, start, firstEnd, "---") then
      None
    else
      closingLine(text, firstEnd + 1).map { (closeStart, closeEnd) =>
        (closeStart, (closeEnd + 1).min(text.length))
      }

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
