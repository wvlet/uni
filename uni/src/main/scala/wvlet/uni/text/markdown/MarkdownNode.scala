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

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.text.Span
import wvlet.uni.text.yaml.Frontmatter
import wvlet.uni.weaver.Weaver

/**
  * A parsed Markdown document
  *
  * @param frontmatter
  *   the leading YAML header, if any (see [[MarkdownConfig.frontmatter]]). It is not rendered.
  */
case class MarkdownDocument(blocks: Seq[Block], frontmatter: Option[JSONValue] = None):
  def toHtml(config: MarkdownConfig = MarkdownConfig()): String = HtmlRenderer(config).render(this)

  /**
    * Decode the frontmatter into `A`. A missing or empty header decodes as an empty mapping, so
    * fields take their default values.
    */
  def frontmatterAs[A](using Weaver[A]): A = Frontmatter(frontmatter, "").as[A]

/**
  * Block-level nodes. `span` covers the source lines of the block, as offsets in the input with
  * normalized line breaks.
  */
enum Block:
  def span: Span

  case Heading(level: Int, inlines: Seq[Inline], span: Span)
  case Paragraph(inlines: Seq[Inline], span: Span)
  case BlockQuote(blocks: Seq[Block], span: Span)

  /**
    * A bullet (`ordered = false`) or ordered list. In a tight list, item paragraphs render without
    * `<p>` tags.
    */
  case ListBlock(ordered: Boolean, start: Int, tight: Boolean, items: Seq[ListItem], span: Span)

  /**
    * A fenced or indented code block. `info` is the text after the opening fence (empty for
    * indented code); its first word is the language.
    */
  case CodeBlock(info: String, code: String, fenced: Boolean, span: Span)

  case HtmlBlock(html: String, span: Span)
  case ThematicBreak(span: Span)

  /**
    * A GitHub Flavored Markdown table
    */
  case Table(
      header: Seq[Seq[Inline]],
      alignments: Seq[TableAlignment],
      rows: Seq[Seq[Seq[Inline]]],
      span: Span
  )

end Block

/**
  * A list item. `checked` is defined for a GitHub Flavored Markdown task-list item (`- [x] done`).
  */
case class ListItem(blocks: Seq[Block], checked: Option[Boolean], span: Span)

enum TableAlignment:
  case Default,
    Left,
    Center,
    Right

/**
  * Inline nodes
  */
enum Inline:
  case Text(text: String)
  case Code(code: String)
  case Emphasis(children: Seq[Inline])
  case Strong(children: Seq[Inline])
  case Strikethrough(children: Seq[Inline])
  case Link(destination: String, title: Option[String], children: Seq[Inline])
  case Image(destination: String, title: Option[String], alt: Seq[Inline])
  case HtmlInline(html: String)
  case SoftBreak
  case HardBreak

object Inline:

  /**
    * The plain text of inline nodes, without markup (used for image `alt` text)
    */
  def plainText(inlines: Seq[Inline]): String =
    val sb                          = StringBuilder()
    def loop(xs: Seq[Inline]): Unit = xs.foreach {
      case Text(t) =>
        sb.append(t)
      case Code(c) =>
        sb.append(c)
      case Emphasis(c) =>
        loop(c)
      case Strong(c) =>
        loop(c)
      case Strikethrough(c) =>
        loop(c)
      case Link(_, _, c) =>
        loop(c)
      case Image(_, _, alt) =>
        loop(alt)
      case HtmlInline(h) =>
        sb.append(h)
      case SoftBreak | HardBreak =>
        sb.append('\n')
    }
    loop(inlines)
    sb.result()

end Inline
