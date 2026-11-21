package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.{IconButton, Tooltip}

import scala.language.implicitConversions
import scala.scalajs.js
import scala.util.matching.Regex

/**
  * The code panel: the story's `// #region` block with line numbers, a link to the lines on GitHub,
  * and a copy button. Each line is a `span.code-line` with `data-line` set to its line number in
  * the source file. It marks the lines `link` points at, and hovering a line points `link` at it,
  * so the preview outlines what that line creates. When a playground changes a prop, the lines that
  * changed flash.
  */
class CodeView(story: Rx[Story], link: Link) extends RxElement:
  private val copied                    = Rx.variable(false)
  private var root: Option[dom.Element] = None
  private var subscription              = Cancelable.empty
  // The code shown last, to flash the lines a playground change rewrote
  private var previous: (String, Seq[String]) = ("", Seq.empty)

  private def copy(code: String): Unit =
    dom.window.navigator.clipboard.writeText(code)
    copied := true
    dom.window.setTimeout(() => copied := false, 1500)

  override def onMount(node: Any): Unit =
    root = Some(node.asInstanceOf[dom.Element])
    // Bring the line into view when the preview points at it
    subscription = link
      .lit
      .run { l =>
        if l.from == LinkSource.Preview then
          l.lines
            .headOption
            .foreach { n =>
              root
                .flatMap(r => Option(r.querySelector(s".code-line[data-line='${n}']")))
                .foreach(
                  _.asInstanceOf[js.Dynamic]
                    .scrollIntoView(js.Dynamic.literal(block = "nearest", behavior = "smooth"))
                )
            }
      }

  override def beforeUnmount: Unit = subscription.cancel

  private def line(text: String, number: Int, changed: Boolean): RxElement = span(
    cls ->
      link
        .lit
        .map { l =>
          val lit =
            if l.lines.contains(number) then
              " is-lit"
            else
              ""
          val chg =
            if changed then
              " is-changed"
            else
              ""
          s"code-line${lit}${chg}"
        },
    data("line") -> number.toString,
    onmouseenter -> { () =>
      link.pointAtLine(number, LinkSource.Code)
    },
    onmouseleave -> { () =>
      link.clear(LinkSource.Code)
    },
    span(cls -> "code-ln", number.toString),
    span(cls -> "code-text", ScalaHighlight.tokens(text).map(_.node))
  )

  private def lines(s: Story, src: StorySource, code: String): Seq[RxElement] =
    val now = code.split("\n", -1).toSeq
    // Flash only the lines a prop change rewrote in the same story, not a story switch
    val before =
      if previous._1 == s.id then
        previous._2
      else
        now
    previous = (s.id, now)
    now
      .zipWithIndex
      .map((text, i) => line(text, src.startLine + i, before.lift(i).exists(_ != text)))

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
                ).onClick(() => s.code.run(c => copy(c)).cancel)
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
            pre(cls -> "code-block", code(s.code.map(c => lines(s, src, c))))
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
