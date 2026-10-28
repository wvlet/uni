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
  * A parsed Markdown document. `toMarkdown` writes it back: unchanged nodes print their original
  * text byte for byte, including blank lines, whitespace, and line breaks, and changed or new nodes
  * print in a canonical style that keeps their original markers.
  *
  * @param frontmatter
  *   the leading YAML header, if any (see [[MarkdownConfig.frontmatter]]). It is not rendered.
  */
case class MarkdownDocument(
    blocks: Seq[Block],
    frontmatter: Option[JSONValue] = None,
    source: Source = Source.none
):
  def toHtml(config: MarkdownConfig = MarkdownConfig()): String = HtmlRenderer(config).render(this)
  def toMarkdown: String                                        = MarkdownWriter.write(this)

  /**
    * Decode the frontmatter into `A`. A missing or empty header decodes as an empty mapping, so
    * fields take their default values.
    */
  def frontmatterAs[A](using Weaver[A]): A = Frontmatter(frontmatter, "").as[A]

/**
  * The source text a node was parsed from, attached so that [[MarkdownDocument.toMarkdown]] can
  * write unchanged nodes back exactly. Like positions in a compiler, it does not take part in
  * equality: two nodes with the same content are equal however they were written.
  *
  * @param raw
  *   the exact text of the node, including container prefixes (`> `) on its later lines
  * @param leading
  *   the text between the previous sibling and this node: blank lines, line breaks, and the
  *   container prefixes of those lines
  * @param opening
  *   for a container, the text before its first child: the `>` or list marker, or the frontmatter
  *   and leading blank lines of a document
  * @param trailing
  *   for a container, the text after its last child
  * @param span
  *   the offsets of `raw` in the input
  */
final class Source private[markdown] (
    val raw: String,
    val leading: String,
    val opening: String,
    val trailing: String,
    val span: Span,
    private[markdown] val parsed: Product | Null
):
  def isEmpty: Boolean = parsed == null

  override def equals(other: Any): Boolean = other.isInstanceOf[Source]
  override def hashCode: Int               = 0
  override def toString: String            =
    if isEmpty then
      "Source.none"
    else
      s"Source(${span})"

object Source:
  /**
    * The source of a node built in code; `toMarkdown` writes it in the canonical style
    */
  val none: Source = Source("", "", "", "", Span.NoSpan, null)

/**
  * Block-level nodes. `span` covers the source text of the block, as offsets in the input.
  */
enum Block:
  def source: Source
  def span: Span = source.span

  case Heading(level: Int, inlines: Seq[Inline], source: Source = Source.none)
  case Paragraph(inlines: Seq[Inline], source: Source = Source.none)
  case BlockQuote(blocks: Seq[Block], source: Source = Source.none)

  /**
    * A bullet (`ordered = false`) or ordered list. In a tight list, item paragraphs render without
    * `<p>` tags.
    */
  case ListBlock(
      ordered: Boolean,
      start: Int,
      tight: Boolean,
      items: Seq[ListItem],
      source: Source = Source.none
  )

  /**
    * A fenced or indented code block. `info` is the text after the opening fence (empty for
    * indented code); its first word is the language.
    */
  case CodeBlock(info: String, code: String, fenced: Boolean, source: Source = Source.none)
  case HtmlBlock(html: String, source: Source = Source.none)
  case ThematicBreak(source: Source = Source.none)

  /**
    * A GitHub Flavored Markdown table
    */
  case Table(
      header: Seq[Seq[Inline]],
      alignments: Seq[TableAlignment],
      rows: Seq[Seq[Seq[Inline]]],
      source: Source = Source.none
  )

end Block

/**
  * A list item. `checked` is defined for a GitHub Flavored Markdown task-list item (`- [x] done`).
  */
case class ListItem(blocks: Seq[Block], checked: Option[Boolean], source: Source = Source.none):
  def span: Span = source.span

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
