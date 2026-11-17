package example.renderer

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.HtmlTags
import wvlet.uni.text.markdown.{Block, Inline, ListItem, Markdown, TableAlignment}

import scala.language.implicitConversions

/**
  * Markdown text (a reply) as elements, built from the parsed AST with no `innerHTML`.
  *
  *   - Raw HTML in the text shows as text.
  *   - Only `http`, `https`, and `mailto` links are clickable. A click calls `openLink` instead of
  *     navigating the window: in Electron, a navigation would load the page in the window that has
  *     the preload bridge.
  *   - Images show as a link with their alt text, so a reply loads nothing from remote hosts.
  *
  * Rendering parses the whole text, so a reply that grows as it streams in renders again as a
  * whole, and a half-written list or code fence settles as more text arrives.
  *
  * @param source
  *   the Markdown text
  * @param openLink
  *   opens a clicked link's URL, such as [[Ui.openLink]]
  */
class MarkdownView(source: String, openLink: String => Unit) extends RxElement:

  override def render: RxElement = div(cls -> "markdown", Markdown.parse(source).blocks.map(block))

  private def block(b: Block): RxElement =
    b match
      case Block.Paragraph(inlines, _) =>
        p(inlines.map(inline))
      case Block.Heading(level, inlines, _) =>
        HtmlTags.tag(s"h${level.max(1).min(6)}")(inlines.map(inline))
      case Block.BlockQuote(blocks, _) =>
        blockquote(blocks.map(block))
      case Block.ListBlock(ordered, start, tight, items, _) =>
        val list =
          if ordered then
            ol(HtmlTags.attr("start") -> start.toString)
          else
            ul()
        list(items.map(item(_, tight)))
      case Block.CodeBlock(info, code, _, _) =>
        val language = info.trim.takeWhile(!_.isWhitespace)
        pre(
          if language.isEmpty then
            DomNode.empty
          else
            HtmlTags.attr("data-language") -> language
          ,
          HtmlTags.tag("code")(code)
        )
      case Block.HtmlBlock(html, _) =>
        // Not trusted: show the markup as text
        pre(cls -> "markdown-html", html)
      case Block.ThematicBreak(_) =>
        hr()
      case Block.Table(header, alignments, rows, _) =>
        def cell(t: DomElement, i: Int, inlines: Seq[Inline]): RxElement = t(
          alignments.lift(i) match
            case Some(TableAlignment.Left) =>
              styleAttr -> "text-align: left"
            case Some(TableAlignment.Center) =>
              styleAttr -> "text-align: center"
            case Some(TableAlignment.Right) =>
              styleAttr -> "text-align: right"
            case _ =>
              DomNode.empty
          ,
          inlines.map(inline)
        )
        div(
          cls -> "markdown-table",
          table(
            thead(tr(header.zipWithIndex.map((c, i) => cell(th, i, c)))),
            tbody(rows.map(r => tr(r.zipWithIndex.map((c, i) => cell(td, i, c)))))
          )
        )

  // A tight list shows its paragraphs inline, as `Markdown.toHtml` does
  private def item(it: ListItem, tight: Boolean): RxElement =
    val body = it
      .blocks
      .flatMap {
        case Block.Paragraph(inlines, _) if tight =>
          inlines.map(inline)
        case b =>
          Seq(block(b))
      }
    li(
      it.checked match
        case Some(done) =>
          Seq[DomNode](
            cls -> "markdown-task",
            input(tpe -> "checkbox", disabled -> true, checked -> done)
          )
        case None =>
          Seq.empty[DomNode]
      ,
      body
    )

  private def inline(x: Inline): DomNode =
    x match
      case Inline.Text(text, _) =>
        text
      case Inline.Code(code, _) =>
        HtmlTags.tag("code")(code)
      case Inline.Emphasis(children, _) =>
        em(children.map(inline))
      case Inline.Strong(children, _) =>
        strong(children.map(inline))
      case Inline.Strikethrough(children, _) =>
        HtmlTags.tag("del")(children.map(inline))
      case Inline.Link(url, title, children, _) =>
        link(url, title, children.map(inline))
      case Inline.Image(url, title, alt, _) =>
        val text = Inline.plainText(alt)
        link(
          url,
          title,
          Seq(
            if text.isEmpty then
              "image"
            else
              text
          )
        )
      case Inline.HtmlInline(html, _) =>
        // Not trusted: show the markup as text
        html
      case Inline.SoftBreak(_) =>
        "\n"
      case Inline.HardBreak(_) =>
        br()

  private def link(url: String, title: Option[String], content: Seq[DomNode]): DomNode =
    if MarkdownView.isSafeLink(url) then
      a(
        href   -> url,
        target -> "_blank",
        rel    -> "noopener noreferrer",
        title.fold(DomNode.empty)(t => titleAttr -> t),
        onclick -> { (e: dom.MouseEvent) =>
          e.preventDefault()
          openLink(url)
        },
        content
      )
    else
      span(content)

end MarkdownView

object MarkdownView:
  private val safeSchemes = Seq("http://", "https://", "mailto:")

  /** True for links a reply may open: `http`, `https`, and `mailto` URLs. */
  def isSafeLink(url: String): Boolean =
    val u = url.trim.toLowerCase
    safeSchemes.exists(u.startsWith)
