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

import MarkdownText.escapeHtml

/**
  * Renders a [[MarkdownDocument]] to HTML in the same layout as the CommonMark reference renderer
  */
private[markdown] class HtmlRenderer(config: MarkdownConfig):
  private val out = StringBuilder()

  def render(doc: MarkdownDocument): String =
    out.clear()
    doc.blocks.foreach(renderBlock(_, tight = false))
    out.result()

  /**
    * Start a new line unless the output already ends with one
    */
  private def cr(): Unit =
    if out.nonEmpty && out.last != '\n' then
      out.append('\n')

  private def renderBlock(b: Block, tight: Boolean): Unit =
    b match
      case Block.Paragraph(inlines, _) =>
        if tight then
          renderInlines(inlines)
        else
          cr()
          out.append("<p>")
          renderInlines(inlines)
          out.append("</p>")
          cr()
      case Block.Heading(level, inlines, _) =>
        cr()
        out.append(s"<h${level}>")
        renderInlines(inlines)
        out.append(s"</h${level}>")
        cr()
      case Block.BlockQuote(blocks, _) =>
        cr()
        out.append("<blockquote>")
        cr()
        blocks.foreach(renderBlock(_, tight = false))
        cr()
        out.append("</blockquote>")
        cr()
      case Block.ListBlock(ordered, start, isTight, items, _) =>
        val tag =
          if ordered then
            "ol"
          else
            "ul"
        cr()
        out.append(s"<${tag}")
        if ordered && start != 1 then
          out.append(s""" start="${start}"""")
        out.append(">")
        cr()
        items.foreach { item =>
          out.append("<li>")
          item
            .checked
            .foreach { checked =>
              out.append(
                if checked then
                  """<input checked="" disabled="" type="checkbox"> """
                else
                  """<input disabled="" type="checkbox"> """
              )
            }
          item.blocks.foreach(renderBlock(_, isTight))
          out.append("</li>")
          cr()
        }
        cr()
        out.append(s"</${tag}>")
        cr()
      case Block.CodeBlock(info, code, _, _) =>
        cr()
        out.append("<pre><code")
        val lang = info.takeWhile(c => c != ' ' && c != '\t')
        if lang.nonEmpty then
          out.append(s""" class="language-${escapeHtml(lang)}"""")
        out.append(">")
        out.append(escapeHtml(code))
        out.append("</code></pre>")
        cr()
      case Block.HtmlBlock(html, _) =>
        cr()
        if config.rawHtml then
          out.append(html)
        else
          out.append(escapeHtml(html))
        cr()
      case Block.ThematicBreak(_) =>
        cr()
        out.append("<hr />")
        cr()
      case Block.Table(header, alignments, rows, _) =>
        cr()
        out.append("<table>\n<thead>\n<tr>\n")
        header.zipWithIndex.foreach((cell, i) => renderCell("th", cell, alignments(i)))
        out.append("</tr>\n</thead>\n")
        if rows.nonEmpty then
          out.append("<tbody>\n")
          rows.foreach { row =>
            out.append("<tr>\n")
            row.zipWithIndex.foreach((cell, i) => renderCell("td", cell, alignments(i)))
            out.append("</tr>\n")
          }
          out.append("</tbody>\n")
        out.append("</table>")
        cr()

  private def renderCell(tag: String, cell: Seq[Inline], alignment: TableAlignment): Unit =
    val align =
      alignment match
        case TableAlignment.Default =>
          ""
        case a =>
          s""" align="${a.toString.toLowerCase}""""
    out.append(s"<${tag}${align}>")
    renderInlines(cell)
    out.append(s"</${tag}>\n")

  private def renderInlines(inlines: Seq[Inline]): Unit = inlines.foreach {
    case Inline.Text(t) =>
      out.append(escapeHtml(t))
    case Inline.Code(c) =>
      out.append("<code>").append(escapeHtml(c)).append("</code>")
    case Inline.Emphasis(c) =>
      out.append("<em>")
      renderInlines(c)
      out.append("</em>")
    case Inline.Strong(c) =>
      out.append("<strong>")
      renderInlines(c)
      out.append("</strong>")
    case Inline.Strikethrough(c) =>
      out.append("<del>")
      renderInlines(c)
      out.append("</del>")
    case Inline.Link(dest, title, c) =>
      out.append("<a href=\"").append(escapeHtml(safeUrl(dest, image = false))).append('"')
      title
        .filter(_.nonEmpty)
        .foreach(t => out.append(" title=\"").append(escapeHtml(t)).append('"'))
      out.append(">")
      renderInlines(c)
      out.append("</a>")
    case Inline.Image(dest, title, alt) =>
      out.append("<img src=\"").append(escapeHtml(safeUrl(dest, image = true))).append('"')
      out.append(" alt=\"").append(escapeHtml(Inline.plainText(alt))).append('"')
      title
        .filter(_.nonEmpty)
        .foreach(t => out.append(" title=\"").append(escapeHtml(t)).append('"'))
      out.append(" />")
    case Inline.HtmlInline(html) =>
      if config.rawHtml then
        out.append(html)
      else
        out.append(escapeHtml(html))
    case Inline.SoftBreak =>
      out.append('\n')
    case Inline.HardBreak =>
      out.append("<br />\n")
  }

  /**
    * Drop `javascript:`, `vbscript:`, `file:`, and non-image `data:` URLs unless raw HTML is
    * trusted
    */
  private def safeUrl(url: String, image: Boolean): String =
    if config.rawHtml then
      url
    else
      val lower  = url.trim.toLowerCase
      val unsafe =
        lower.startsWith("javascript:") || lower.startsWith("vbscript:") ||
          lower.startsWith("file:") ||
          lower.startsWith("data:") && !(
            image &&
              Seq("data:image/png", "data:image/gif", "data:image/jpeg", "data:image/webp").exists(
                lower.startsWith
              )
          )
      if unsafe then
        ""
      else
        url

end HtmlRenderer
