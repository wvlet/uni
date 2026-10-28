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

import wvlet.uni.text.Span

import scala.collection.mutable

/**
  * Markdown parsing and HTML rendering options
  *
  * @param rawHtml
  *   pass raw HTML and every link URL through to the output. Only for trusted input. When false
  *   (the default), raw HTML is escaped and `javascript:`, `vbscript:`, `file:`, and non-image
  *   `data:` URLs are dropped.
  * @param gfm
  *   enable the GitHub Flavored Markdown extensions: tables, task lists, strikethrough, and
  *   extended autolinks. When false, the parser follows CommonMark only.
  * @param frontmatter
  *   read a leading YAML header between `---` lines into [[MarkdownDocument.frontmatter]] instead
  *   of rendering it. A header that is not a YAML mapping stays Markdown. When false, a leading
  *   `---` is a thematic break, as in CommonMark.
  */
case class MarkdownConfig(
    rawHtml: Boolean = false,
    gfm: Boolean = true,
    frontmatter: Boolean = true
):
  def withRawHtml(enabled: Boolean): MarkdownConfig     = copy(rawHtml = enabled)
  def withGfm(enabled: Boolean): MarkdownConfig         = copy(gfm = enabled)
  def withFrontmatter(enabled: Boolean): MarkdownConfig = copy(frontmatter = enabled)

/**
  * Parse CommonMark with GitHub Flavored Markdown extensions, and render it to HTML.
  *
  * {{{
  *   Markdown.toHtml("# Hello\n\n- [x] *done*")
  *   // <h1>Hello</h1>
  *   // <ul>
  *   // <li><input checked="" disabled="" type="checkbox"> <em>done</em></li>
  *   // </ul>
  * }}}
  */
object Markdown:

  def parse(markdown: String, config: MarkdownConfig = MarkdownConfig()): MarkdownDocument =
    val parser = BlockParser(markdown, config.gfm, config.frontmatter)
    val root   = parser.parse()
    Converter(parser, markdown, config.gfm).document(root)

  def toHtml(markdown: String, config: MarkdownConfig = MarkdownConfig()): String = parse(
    markdown,
    config
  ).toHtml(config)

  /**
    * Converts the parsed block tree into [[Block]]s, parsing inline content once all link reference
    * definitions are known, and attaches each node's [[Source]]
    */
  private class Converter(parser: BlockParser, input: String, gfm: Boolean):

    // The normalized-text extent of each node, widened to cover its children
    private val extents = mutable.HashMap.empty[BlockNode, (Int, Int)]

    private def extent(b: BlockNode): (Int, Int) = extents.getOrElseUpdate(
      b, {
        val span  = parser.spanOf(b)
        var start = span.start
        var end   = span.end
        b.children
          .foreach { c =>
            val (cs, ce) = extent(c)
            start = start.min(cs)
            end = end.max(ce)
          }
        // A line break after the node belongs to the trivia that follows
        while end > start && parser.charAt(end - 1) == '\n' do
          end -= 1
        (start, end)
      }
    )

    private def raw(start: Int, end: Int): String = input.substring(
      parser.rawOffset(start),
      parser.rawOffset(end)
    )

    private def source(start: Int, end: Int, leading: String, parsed: Product): Source = source(
      start,
      end,
      leading,
      "",
      "",
      parsed
    )

    private def source(
        start: Int,
        end: Int,
        leading: String,
        opening: String,
        trailing: String,
        parsed: Product
    ): Source = Source(
      raw(start, end),
      leading,
      opening,
      trailing,
      Span.within(parser.rawOffset(start), parser.rawOffset(end)),
      parsed
    )

    def document(root: BlockNode): MarkdownDocument =
      val c      = children(root.children.toSeq, 0, parser.textLength)(block)
      val blocks = c.nodes
      MarkdownDocument(
        blocks,
        parser.header,
        Source(
          raw(0, parser.textLength),
          "",
          c.opening,
          c.trailing,
          Span.within(0, input.length),
          Tuple1(blocks),
          parser.refMap.toMap
        )
      )

    private case class Children[A](nodes: Seq[A], opening: String, trailing: String)

    /**
      * Convert the children of a node spanning [from, to). Each child gets the text since the
      * previous sibling as its leading trivia; the text before the first child and after the last
      * one go to the parent.
      */
    private def children[A](nodes: Seq[BlockNode], from: Int, to: Int)(
        convert: (BlockNode, Int, Int, String) => A
    ): Children[A] =
      var prev    = from
      var opening = ""
      val out     = Seq.newBuilder[A]
      nodes
        .zipWithIndex
        .foreach { (n, i) =>
          val (s, e) = extent(n)
          val start  = s.max(prev).min(to)
          val end    = e.max(start).min(to)
          if i == 0 then
            opening = raw(from, start)
          out +=
            convert(
              n,
              start,
              end,
              if i == 0 then
                ""
              else
                raw(prev, start)
            )
          prev = end
        }
      if nodes.isEmpty then
        Children(Nil, raw(from, to), "")
      else
        Children(out.result(), opening, raw(prev, to))

    end children

    private def inlines(s: String): Seq[Inline] = InlineParser(s.trim, parser.refMap, gfm).parse()

    private def block(b: BlockNode, start: Int, end: Int, leading: String): Block =
      def leaf(parsed: Product): Source = source(start, end, leading, parsed)
      def container[A](nodes: Seq[BlockNode])(
          convert: (BlockNode, Int, Int, String) => A
      ): (Seq[A], Product => Source) =
        val c = children(nodes, start, end)(convert)
        (c.nodes, parsed => source(start, end, leading, c.opening, c.trailing, parsed))

      b.kind match
        case BlockKind.Paragraph =>
          val in = inlines(b.content.toString)
          Block.Paragraph(in, leaf(Tuple1(in)))
        case BlockKind.Heading =>
          val in = inlines(b.content.toString)
          Block.Heading(b.level, in, leaf((b.level, in)))
        case BlockKind.BlockQuote =>
          val (blocks, src) = container(b.children.toSeq)(block)
          Block.BlockQuote(blocks, src(Tuple1(blocks)))
        case BlockKind.ListKind =>
          val data         = b.listData.nn
          val (items, src) = container(b.children.toSeq)(item)
          Block.ListBlock(
            data.ordered,
            data.start,
            data.tight,
            items,
            src((data.ordered, data.start, data.tight, items))
          )
        case BlockKind.Code =>
          Block.CodeBlock(b.info, b.literal, b.fenced, leaf((b.info, b.literal, b.fenced)))
        case BlockKind.Html =>
          Block.HtmlBlock(b.literal, leaf(Tuple1(b.literal)))
        case BlockKind.TableKind =>
          val columns                              = b.tableAlignments.size
          def cells(row: String): Seq[Seq[Inline]] =
            val raw = BlockParser.splitTableRow(row).take(columns)
            (raw ++ Seq.fill(columns - raw.size)("")).map(inlines)
          val header = cells(b.tableHeader)
          val rows   = b.tableRows.toSeq.map(cells)
          Block.Table(header, b.tableAlignments, rows, leaf((header, b.tableAlignments, rows)))
        case _ =>
          Block.ThematicBreak(leaf(Tuple1(())))
      end match

    end block

    private def item(b: BlockNode, start: Int, end: Int, leading: String): ListItem =
      val task =
        if gfm then
          b.children.headOption.filter(_.kind == BlockKind.Paragraph).flatMap(taskMarker)
        else
          None
      val c = children(b.children.toSeq, start, end)(block)
      ListItem(c.nodes, task, source(start, end, leading, c.opening, c.trailing, (c.nodes, task)))

    /**
      * Detect a task-list marker (`[ ]`, `[x]`) at the start of the item's first paragraph, and
      * remove it from the paragraph text
      */
    private def taskMarker(p: BlockNode): Option[Boolean] =
      val s = p.content.toString
      if s.length > 3 && s.charAt(0) == '[' && s.charAt(2) == ']' &&
        (s.charAt(3) == ' ' || s.charAt(3) == '\t') && " xX".indexOf(s.charAt(1)) >= 0
      then
        p.content.delete(0, 4)
        Some(s.charAt(1) != ' ')
      else
        None

  end Converter

end Markdown
