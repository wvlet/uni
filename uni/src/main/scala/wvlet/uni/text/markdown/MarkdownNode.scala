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
import wvlet.uni.text.yaml.FrontmatterSource
import wvlet.uni.weaver.Weaver

/**
  * A parsed Markdown document. `toMarkdown` writes it back: unchanged nodes print their original
  * text byte for byte, including blank lines, whitespace, and line breaks, and changed or new nodes
  * print in a canonical style that keeps their original markers.
  *
  * @param frontmatter
  *   the leading YAML header, if any (see [[MarkdownConfig.frontmatter]]). It is not rendered.
  *   `toMarkdown` writes changes to it back, keeping the header's comments and formatting.
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
    private[markdown] val parsed: Product | Null,
    // A document's link reference definitions, to check rewritten text against
    private[markdown] val definitions: Map[String, (String, Option[String])] = Map.empty,
    // A document's frontmatter header as read, to write changes to the header back
    private[markdown] val frontmatter: FrontmatterSource = FrontmatterSource.none
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
    * The raw text of an inline node, before [[seal]] records its content
    */
  private[markdown] def unsealed(raw: String): Source = Source(raw, "", "", "", Span.NoSpan, null)

  /**
    * Record the content of each inline node in its source, once the tree is final
    */
  private[markdown] def seal(inlines: Seq[Inline]): Seq[Inline] = inlines.map { i =>
    def s(parsed: Product): Source =
      val src = i.source
      if src eq Source.none then
        Source.none
      else
        Source(src.raw, "", "", "", Span.NoSpan, parsed)
    i match
      case Inline.Emphasis(c, _) =>
        val children = seal(c)
        Inline.Emphasis(children, s(Tuple1(children)))
      case Inline.Strong(c, _) =>
        val children = seal(c)
        Inline.Strong(children, s(Tuple1(children)))
      case Inline.Strikethrough(c, _) =>
        val children = seal(c)
        Inline.Strikethrough(children, s(Tuple1(children)))
      case Inline.Link(d, t, c, _) =>
        val children = seal(c)
        Inline.Link(d, t, children, s((d, t, children)))
      case Inline.Image(d, t, c, _) =>
        val children = seal(c)
        Inline.Image(d, t, children, s((d, t, children)))
      case other =>
        val src = other.source
        if src eq Source.none then
          other
        else
          withSource(other, Source(src.raw, "", "", "", Span.NoSpan, content(other)))
  }

  private def withSource(i: Inline, s: Source): Inline =
    i match
      case x: Inline.Text =>
        x.copy(source = s)
      case x: Inline.Code =>
        x.copy(source = s)
      case x: Inline.Emphasis =>
        x.copy(source = s)
      case x: Inline.Strong =>
        x.copy(source = s)
      case x: Inline.Strikethrough =>
        x.copy(source = s)
      case x: Inline.Link =>
        x.copy(source = s)
      case x: Inline.Image =>
        x.copy(source = s)
      case x: Inline.HtmlInline =>
        x.copy(source = s)
      case x: Inline.SoftBreak =>
        x.copy(source = s)
      case x: Inline.HardBreak =>
        x.copy(source = s)

  /**
    * The content fields of a node: a node prints its source text while these equal [[parsed]]
    */
  private[markdown] def content(b: Block): Product =
    b match
      case Block.Paragraph(inlines, _) =>
        Tuple1(inlines)
      case Block.Heading(level, inlines, _) =>
        (level, inlines)
      case Block.BlockQuote(blocks, _) =>
        Tuple1(blocks)
      case Block.ListBlock(ordered, start, tight, items, _) =>
        (ordered, start, tight, items)
      case Block.CodeBlock(info, code, fenced, _) =>
        (info, code, fenced)
      case Block.HtmlBlock(html, _) =>
        Tuple1(html)
      case Block.ThematicBreak(_) =>
        Tuple1(())
      case Block.Table(header, alignments, rows, _) =>
        (header, alignments, rows)

  private[markdown] def content(i: Inline): Product =
    i match
      case Inline.Text(t, _) =>
        Tuple1(t)
      case Inline.Code(c, _) =>
        Tuple1(c)
      case Inline.Emphasis(c, _) =>
        Tuple1(c)
      case Inline.Strong(c, _) =>
        Tuple1(c)
      case Inline.Strikethrough(c, _) =>
        Tuple1(c)
      case Inline.Link(d, t, c, _) =>
        (d, t, c)
      case Inline.Image(d, t, c, _) =>
        (d, t, c)
      case Inline.HtmlInline(h, _) =>
        Tuple1(h)
      case Inline.SoftBreak(_) | Inline.HardBreak(_) =>
        Tuple1(())

  /**
    * Is the content still what was parsed, so that the source text can be printed as is?
    */
  private[markdown] def same(parsed: Product | Null, current: Product): Boolean =
    parsed match
      case null =>
        false
      case p: Product =>
        p.productArity == current.productArity &&
        p.productIterator
          .zip(current.productIterator)
          .forall { (a, b) =>
            a.asInstanceOf[AnyRef].eq(b.asInstanceOf[AnyRef]) || a == b
          }

end Source

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
  * Inline nodes. Their [[Source]] holds the raw text in the paragraph, without container prefixes.
  */
enum Inline:
  def source: Source

  case Text(text: String, source: Source = Source.none)
  case Code(code: String, source: Source = Source.none)
  case Emphasis(children: Seq[Inline], source: Source = Source.none)
  case Strong(children: Seq[Inline], source: Source = Source.none)
  case Strikethrough(children: Seq[Inline], source: Source = Source.none)
  case Link(
      destination: String,
      title: Option[String],
      children: Seq[Inline],
      source: Source = Source.none
  )

  case Image(
      destination: String,
      title: Option[String],
      alt: Seq[Inline],
      source: Source = Source.none
  )

  case HtmlInline(html: String, source: Source = Source.none)
  case SoftBreak(source: Source = Source.none)
  case HardBreak(source: Source = Source.none)

object Inline:

  /**
    * The plain text of inline nodes, without markup (used for image `alt` text)
    */
  def plainText(inlines: Seq[Inline]): String =
    val sb                          = StringBuilder()
    def loop(xs: Seq[Inline]): Unit = xs.foreach {
      case Text(t, _) =>
        sb.append(t)
      case Code(c, _) =>
        sb.append(c)
      case Emphasis(c, _) =>
        loop(c)
      case Strong(c, _) =>
        loop(c)
      case Strikethrough(c, _) =>
        loop(c)
      case Link(_, _, c, _) =>
        loop(c)
      case Image(_, _, alt, _) =>
        loop(alt)
      case HtmlInline(h, _) =>
        sb.append(h)
      case SoftBreak(_) | HardBreak(_) =>
        sb.append('\n')
    }
    loop(inlines)
    sb.result()

end Inline
