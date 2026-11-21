package example.gallery

import example.gallery.SourceTag.Tag
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Rx, RxVar}

import scala.language.implicitConversions

/**
  * The code of a story, cut from its `// #region` block at build time (project/StoryRegions.scala),
  * with the file and lines it came from.
  */
case class StorySource(code: String, file: String, startLine: Int, endLine: Int):
  def githubUrl: String =
    s"https://github.com/wvlet/uni/blob/main/${file}#L${startLine}-L${endLine}"

/**
  * One demo in the gallery. `id` is both its route (`#/controls/buttons`) and the name of its
  * `// #region` block, so the code panel shows the code that `render` runs. `tags` are the uni-ui
  * components the code creates and their lines, found by the [[SourceTag]] macro.
  */
case class Story(
    id: String,
    title: String,
    summary: String,
    render: () => RxElement,
    tags: Seq[Tag],
    playground: Option[Playground[?]] = None
):
  def group: String               = id.takeWhile(_ != '/')
  def source: Option[StorySource] = StorySources.all.get(id)

  /** The components the code uses, in the order they first appear. */
  def kinds: Seq[String] = tags.map(_.kind).distinct

  /** The lines that create a `kind` component. */
  def linesOf(kind: String): Set[Int] = tags.filter(_.kind == kind).map(_.line).toSet

  /** The code to show: a playground writes its current props into it. */
  def code: Rx[String] =
    val base = source.map(_.code).getOrElse("")
    playground.map(_.substitute(base)).getOrElse(Rx.const(base))

object Story:
  /** A story whose `view` is the call to its `inline def`, such as `story(...)(buttons)`. */
  inline def apply(id: String, title: String, summary: String)(inline view: RxElement): Story =
    val t = SourceTag(() => view)
    Story(id, title, summary, t.value, t.tags)

  /**
    * A story with controls for the props `P` of its `inline def`. The code panel shows each
    * `p.<name>` as the control's current value, written as Scala.
    */
  inline def playground[P](
      id: String,
      title: String,
      summary: String,
      initial: P,
      controls: Seq[Control[P]]
  )(inline view: P => RxElement): Story =
    val t  = SourceTag(view)
    val pg = Playground(initial, controls, t.value)
    Story(id, title, summary, () => pg.preview, t.tags, Some(pg))

end Story

/** The props of a playground story, the controls that edit them, and the preview they drive. */
class Playground[P](initial: P, val controls: Seq[Control[P]], view: P => RxElement):
  val props: RxVar[P] = Rx.variable(initial)

  def reset(): Unit = props := initial

  // A new element for each change, since options such as withVariant take plain values
  def preview: RxElement = div(cls -> "playground-preview", props.map(view))

  /** `code` with each `p.<name>` replaced by that control's value. */
  def substitute(code: String): Rx[String] = props.map { p =>
    """\bp\.([A-Za-z]+)\b"""
      .r
      .replaceAllIn(
        code,
        m =>
          controls.find(_.name == m.group(1)) match
            case Some(c) =>
              scala.util.matching.Regex.quoteReplacement(c.literal(p))
            case None =>
              m.matched
      )
  }

end Playground

/**
  * One prop of a playground: how to read it, change it, and write it as Scala in the code panel.
  */
trait Control[P]:
  def name: String
  def literal(p: P): String
  def editor(props: RxVar[P]): RxElement

object Control:

  def text[P](name: String, get: P => String, set: (P, String) => P): Control[P] =
    val n = name
    new Control[P]:
      def name: String          = n
      def literal(p: P): String = "\"" + get(p).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      def editor(props: RxVar[P]): RxElement =
        val text = Rx.variable(get(props.get))
        text.run(t =>
          if get(props.get) != t then
            props.update(p => set(p, t))
        )
        props.run(p =>
          if text.get != get(p) then
            text := get(p)
        )
        wvlet.uni.ui.Input(text).withLabel(name)

  def toggle[P](name: String, get: P => Boolean, set: (P, Boolean) => P): Control[P] =
    val n = name
    new Control[P]:
      def name: String                       = n
      def literal(p: P): String              = get(p).toString
      def editor(props: RxVar[P]): RxElement = label(
        cls -> "prop-toggle",
        input(
          tpe      -> "checkbox",
          role     -> "switch",
          checked  -> props.map(get),
          onchange -> { (e: org.scalajs.dom.Event) =>
            val on = e.target.asInstanceOf[org.scalajs.dom.html.Input].checked
            props.update(p => set(p, on))
          }
        ),
        span(cls -> "prop-toggle__track"),
        span(cls -> "prop-toggle__label", name)
      )

  def choice[P, A](
      name: String,
      options: Seq[A],
      get: P => A,
      set: (P, A) => P,
      scala: A => String
  ): Control[P] =
    val n = name
    new Control[P]:
      def name: String                       = n
      def literal(p: P): String              = scala(get(p))
      def editor(props: RxVar[P]): RxElement =
        val selected = Rx.variable(get(props.get))
        selected.run(a =>
          if get(props.get) != a then
            props.update(p => set(p, a))
        )
        props.run(p =>
          if selected.get != get(p) then
            selected := get(p)
        )
        wvlet
          .uni
          .ui
          .Select(selected, options = options, optionLabel = a => a.toString)
          .withLabel(name)

end Control
