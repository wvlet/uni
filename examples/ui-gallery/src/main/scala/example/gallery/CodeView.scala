package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.{IconButton, Tooltip}

import scala.language.implicitConversions
import scala.util.matching.Regex

/**
  * The code panel: the story's `// #region` block with line numbers, a link to the lines on GitHub,
  * and a copy button. Each line is a `span.code-line` with `data-line` set to its line number in
  * the source file. The lines that create the spotlighted component are marked.
  */
class CodeView(story: Rx[Story], spot: Rx[Option[String]] = Rx.const(None)) extends RxElement:
  private val copied = Rx.variable(false)

  private def copy(code: String): Unit =
    dom.window.navigator.clipboard.writeText(code)
    copied := true
    dom.window.setTimeout(() => copied := false, 1500)

  private def line(text: String, number: Int): RxElement = span(
    cls ->
      spot.map {
        case Some(name) if Spotlight.creates(text, name) =>
          "code-line is-spotlit"
        case _ =>
          "code-line"
      },
    data("line") -> number.toString,
    span(cls -> "code-ln", number.toString),
    span(cls -> "code-text", ScalaHighlight.tokens(text).map(_.node))
  )

  override def render: RxElement = div(
    cls -> "code-view",
    story.map { s =>
      s.source match
        case None =>
          div(cls -> "code-missing", s"No // #region ${s.id} block was found for this story.")
        case Some(src) =>
          div(
            cls -> "code-body",
            div(
              cls -> "code-toolbar",
              span(cls -> "code-file", Icons.file, src.file.split('/').last),
              span(cls -> "code-lines", s"L${src.startLine}–${src.endLine}"),
              div(cls  -> "code-toolbar__spacer"),
              Tooltip(
                "Copy the code",
                trigger = IconButton(
                  "Copy the code",
                  copied.map(c =>
                    if c then
                      Icons.check
                    else
                      Icons.copy
                  )
                ).onClick(() => copy(src.code))
              ),
              Tooltip(
                "Open these lines on GitHub",
                trigger = a(
                  cls       -> "code-github",
                  href      -> src.githubUrl,
                  target    -> "_blank",
                  rel       -> "noopener",
                  ariaLabel -> "Open these lines on GitHub",
                  Icons.github
                )
              )
            ),
            pre(
              cls -> "code-block",
              code(
                src
                  .code
                  .split("\n", -1)
                  .toSeq
                  .zipWithIndex
                  .map((text, i) => line(text, src.startLine + i))
              )
            )
          )
    }
  )

end CodeView

/** Just enough Scala highlighting for short story snippets: one line at a time, no parser. */
object ScalaHighlight:
  enum Kind(val cssName: String):
    case Keyword  extends Kind("tok-kw")
    case TypeName extends Kind("tok-type")
    case Str      extends Kind("tok-str")
    case Num      extends Kind("tok-num")
    case Comment  extends Kind("tok-com")
    case Plain    extends Kind("")

  case class Token(text: String, kind: Kind):
    def node: DomNode =
      if kind == Kind.Plain then
        text
      else
        span(cls -> kind.cssName, text)

  private val keywords = Set(
    "case",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "extends",
    "false",
    "for",
    "given",
    "if",
    "import",
    "match",
    "new",
    "null",
    "object",
    "then",
    "this",
    "trait",
    "true",
    "type",
    "using",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

  private val pattern: Regex =
    """(//.*)|([sf]?"(?:\\.|[^"\\])*")|('(?:\\.|[^'\\])')|(\b\d[\d_]*(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)"""
      .r

  def tokens(line: String): Seq[Token] =
    val out  = Seq.newBuilder[Token]
    var last = 0
    pattern
      .findAllMatchIn(line)
      .foreach { m =>
        if m.start > last then
          out += Token(line.substring(last, m.start), Kind.Plain)
        val kind =
          if m.group(1) != null then
            Kind.Comment
          else if m.group(2) != null || m.group(3) != null then
            Kind.Str
          else if m.group(4) != null then
            Kind.Num
          else if keywords.contains(m.matched) then
            Kind.Keyword
          else if m.matched.head.isUpper then
            Kind.TypeName
          else
            Kind.Plain
        out += Token(m.matched, kind)
        last = m.end
      }
    if last < line.length then
      out += Token(line.substring(last), Kind.Plain)
    out.result()

end ScalaHighlight
