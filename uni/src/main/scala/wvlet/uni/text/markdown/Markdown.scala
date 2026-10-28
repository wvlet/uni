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
    MarkdownDocument(Converter(parser, config.gfm).blocks(root), parser.header)

  def toHtml(markdown: String, config: MarkdownConfig = MarkdownConfig()): String = parse(
    markdown,
    config
  ).toHtml(config)

  /**
    * Converts the parsed block tree into [[Block]]s, parsing inline content once all link reference
    * definitions are known
    */
  private class Converter(parser: BlockParser, gfm: Boolean):

    private def inlines(s: String): Seq[Inline] = InlineParser(s.trim, parser.refMap, gfm).parse()

    def blocks(parent: BlockNode): Seq[Block] = parent.children.toSeq.map(block)

    private def block(b: BlockNode): Block =
      val span = parser.spanOf(b)
      b.kind match
        case BlockKind.Paragraph =>
          Block.Paragraph(inlines(b.content.toString), span)
        case BlockKind.Heading =>
          Block.Heading(b.level, inlines(b.content.toString), span)
        case BlockKind.BlockQuote =>
          Block.BlockQuote(blocks(b), span)
        case BlockKind.ListKind =>
          val data = b.listData.nn
          Block.ListBlock(data.ordered, data.start, data.tight, b.children.toSeq.map(item), span)
        case BlockKind.Code =>
          Block.CodeBlock(b.info, b.literal, b.fenced, span)
        case BlockKind.Html =>
          Block.HtmlBlock(b.literal, span)
        case BlockKind.TableKind =>
          val columns                              = b.tableAlignments.size
          def cells(row: String): Seq[Seq[Inline]] =
            val raw = BlockParser.splitTableRow(row).take(columns)
            (raw ++ Seq.fill(columns - raw.size)("")).map(inlines)
          Block.Table(cells(b.tableHeader), b.tableAlignments, b.tableRows.toSeq.map(cells), span)
        case _ =>
          Block.ThematicBreak(span)

    private def item(b: BlockNode): ListItem =
      val task =
        if gfm then
          b.children.headOption.filter(_.kind == BlockKind.Paragraph).flatMap(taskMarker)
        else
          None
      ListItem(blocks(b), task, parser.spanOf(b))

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
