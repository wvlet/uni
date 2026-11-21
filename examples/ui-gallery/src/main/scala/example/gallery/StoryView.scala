package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.{SizeRange, StorageCodec}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*

import scala.language.implicitConversions

/**
  * How a story shows its preview: alone, as code, side by side with its code, or with its agent
  * view.
  */
enum ViewMode(val label: String) derives StorageCodec:
  case Preview extends ViewMode("Preview")
  case Code    extends ViewMode("Code")
  case Split   extends ViewMode("Split")
  case Agent   extends ViewMode("Agent")

object ViewMode:
  def parse(s: String): Option[ViewMode] = values.find(_.toString.equalsIgnoreCase(s))

/** What pointed the preview and the code at something. */
enum LinkSource:
  case Chip,
    Code,
    Preview,
    None

/**
  * The lines of code and the parts of the preview that are marked together. `selector` picks the
  * elements to outline, by the `data-src-kind` and `data-src-line` attributes the [[SourceTag]]
  * macro sets.
  */
case class Lit(lines: Set[Int], selector: Option[String], from: LinkSource)

object Lit:
  val empty: Lit = Lit(Set.empty, None, LinkSource.None)

/**
  * Shared by the preview, the code, and the chips, so each can point at a component for the others.
  */
class Link:
  val lit: RxVar[Lit] = Rx.variable(Lit.empty)

  def pointAtLine(line: Int, from: LinkSource): Unit =
    lit := Lit(Set(line), Some(s"[data-src-line='${line}']"), from)

  def pointAtKind(story: Story, kind: String): Unit =
    lit := Lit(story.linesOf(kind), Some(s"[data-src-kind='${kind}']"), LinkSource.Chip)

  /** Clear the marks, if `from` set them. */
  def clear(from: LinkSource): Unit =
    if lit.get.from == from then
      lit := Lit.empty

/** An outline drawn over the preview, with the component's name, line, slot, and state. */
case class Box(
    top: Double,
    left: Double,
    width: Double,
    height: Double,
    label: String,
    detail: String
)

/**
  * One story: a header with its title and the components it uses, then a card with the live
  * preview, the code, and what an AI agent sees, as tabs or side by side. Hovering a part of the
  * preview with Inspect on, a line of code, or a chip marks the matching part and lines in both.
  */
class StoryView(story: Rx[Story], mode: RxVar[ViewMode], embed: Boolean) extends RxElement:
  private val link       = Link()
  private val inspecting = Rx.variable(false)
  private val boxes      = Rx.variable(Seq.empty[Box])
  private val paneWidth  = Rx.variable(520)
  // Too narrow for side by side: the code or agent view goes below the preview instead
  private val narrow                             = Rx.variable(false)
  private var bodyWidth                          = 0.0
  private var resize: Option[dom.ResizeObserver] = None
  private var root: Option[dom.Element]          = None
  private var subscription                       = Cancelable.empty

  private def preview: Option[dom.Element] = root.flatMap(r =>
    Option(r.querySelector(".story-preview"))
  )

  // Draw a box over each element the link points at, in the preview's scrolled coordinates
  private def draw(l: Lit): Unit =
    val drawn =
      for
        pv  <- preview.toSeq
        sel <- l.selector.toSeq
        stage = pv.querySelector(".story-stage")
        if stage != null
        nodes = stage.querySelectorAll(sel)
        i <- 0 until nodes.length
      yield
        val el     = nodes(i)
        val r      = el.getBoundingClientRect()
        val pr     = pv.getBoundingClientRect()
        val kind   = Option(el.getAttribute("data-src-kind")).getOrElse("")
        val line   = Option(el.getAttribute("data-src-line")).map(n => s" · L${n}").getOrElse("")
        val detail = Seq("data-slot", "data-state", "role")
          .flatMap(a => Option(el.getAttribute(a)).map(v => s"${a.stripPrefix("data-")}=${v}"))
          .mkString("  ")
        Box(
          top = r.top - pr.top + pv.scrollTop,
          left = r.left - pr.left + pv.scrollLeft,
          width = r.width,
          height = r.height,
          label = s"${kind}${line}",
          detail = detail
        )
    boxes := drawn.filter(b => b.width > 0 && b.height > 0)

  // Keep the preview visible: the side pane takes at most 60% of the card, and below 760px the
  // side view stacks under the preview
  private def fit(width: Double): Unit =
    bodyWidth = width
    narrow := width > 0 && width < StoryView.stackBelow
    val max = (width * StoryView.maxShare).toInt
    if width > 0 && paneWidth.get > max then
      paneWidth := math.max(StoryView.minPane, (width * 0.5).toInt)

  override def onMount(node: Any): Unit =
    root = Some(node.asInstanceOf[dom.Element])
    root
      .flatMap(r => Option(r.querySelector(".story-body")))
      .foreach { body =>
        val ro = dom.ResizeObserver((_, _) => fit(body.getBoundingClientRect().width))
        ro.observe(body)
        resize = Some(ro)
        fit(body.getBoundingClientRect().width)
      }
    subscription = Cancelable.merge(
      Seq(
        link.lit.run(draw),
        // A new story starts with nothing marked
        story.run(_ => link.lit := Lit.empty),
        // Dragging the splitter can't push the preview out either
        paneWidth.run(w =>
          if bodyWidth > 0 && w > bodyWidth * StoryView.maxShare then
            fit(bodyWidth)
        )
      )
    )

  override def beforeUnmount: Unit =
    subscription.cancel
    resize.foreach(_.disconnect())
    resize = None

  private def hasSide(m: ViewMode): Boolean = m == ViewMode.Split || m == ViewMode.Agent

  private def side(m: ViewMode): RxElement =
    m match
      case ViewMode.Agent =>
        AgentView(story, () => stage)
      case _ =>
        CodeView(story, link)

  // With Inspect on, hovering a part of the preview points the code at the line that made it
  private def inspect(e: dom.MouseEvent): Unit =
    if inspecting.get then
      e.target match
        case el: dom.Element =>
          Option(el.closest("[data-src-line]")) match
            case Some(tagged) =>
              val line = tagged.getAttribute("data-src-line").toInt
              if !link.lit.get.lines.contains(line) || link.lit.get.from != LinkSource.Preview then
                link.pointAtLine(line, LinkSource.Preview)
            case None =>
              link.clear(LinkSource.Preview)
        case _ =>

  private def stage: Option[dom.Element] = preview.flatMap(p =>
    Option(p.querySelector(".story-stage"))
  )

  override def render: RxElement = div(
    cls -> (
      if embed then
        "story story--embed"
      else
        "example"
    ),
    div(
      cls -> "story-card",
      toolbar,
      story.map(s => s.playground.map(pg => PropsPanel(pg))),
      div(
        cls            -> "story-body",
        data("view")   -> mode.map(_.toString.toLowerCase),
        data("layout") ->
          narrow.map(n =>
            if n then
              "stacked"
            else
              "row"
          ),
        div(
          cls ->
            inspecting.map(on =>
              if on then
                "story-preview is-inspecting"
              else
                "story-preview"
            ),
          onmousemove  -> inspect,
          onmouseleave -> { () =>
            link.clear(LinkSource.Preview)
          },
          onscroll -> { () =>
            draw(link.lit.get)
          },
          story.map(s => div(cls -> "story-stage", s.render())),
          div(
            cls -> "inspect-layer",
            boxes.map(
              _.map { b =>
                div(
                  cls       -> "inspect-box",
                  styleAttr ->
                    s"top: ${b.top}px; left: ${b.left}px; width: ${b.width}px; height: ${b
                        .height}px",
                  span(
                    cls -> "inspect-label",
                    b.label,
                    when(b.detail.nonEmpty, span(cls -> "inspect-detail", b.detail))
                  )
                )
              }
            )
          )
        ),
        mode.map(m =>
          when(m == ViewMode.Code, div(cls -> "story-code-full", CodeView(story, link)))
        ),
        Rx.join(mode, narrow)
          .map((m, n) =>
            when(n && hasSide(m), div(cls -> "story-side story-side--stacked", side(m)))
          ),
        FoldablePane(
          Rx.join(mode, narrow).map((m, n) => !n && hasSide(m)),
          width = paneWidth,
          edge = Edge.Left,
          content = div(cls -> "story-side", mode.map(side))
        ).withSplitter(
          Splitter(
            paneWidth,
            SizeRange(min = StoryView.minPane, default = 520, max = 960),
            Edge.Left,
            "Resize the side panel"
          )
        )
      ),
      // A topic page's examples draw their own events, such as the Rx timelines
      story.map(s => when(Pages.pageOf(s).forall(_.isComponent), EventLog(() => stage)))
    )
  )

  private def chips(s: Story): RxElement = div(
    cls -> "story-chips",
    s.kinds
      .map { kind =>
        val lines = s.linesOf(kind).toSeq.sorted.map(n => s"L${n}").mkString(", ")
        button(
          cls ->
            link
              .lit
              .map(l =>
                if l.from == LinkSource.Chip && l.selector.exists(_.contains(s"'${kind}'")) then
                  "chip is-active"
                else
                  "chip"
              ),
          titleAttr    -> s"${kind} at ${lines}: hover to find it in the preview and the code",
          onmouseenter -> { () =>
            link.pointAtKind(s, kind)
          },
          onmouseleave -> { () =>
            link.clear(LinkSource.Chip)
          },
          onfocus -> { () =>
            link.pointAtKind(s, kind)
          },
          onblur -> { () =>
            link.clear(LinkSource.Chip)
          },
          kind
        )
      }
  )

  private def toolbar: RxElement = div(
    cls -> "story-toolbar",
    // A group of toggle buttons, not a TabList: the views share one body, so there is no
    // tab panel per view for aria-controls to name
    div(
      cls       -> "uni-tabs view-switch",
      role      -> "group",
      ariaLabel -> "View",
      Seq(
        (ViewMode.Preview, Icons.eye),
        (ViewMode.Code, Icons.code),
        (ViewMode.Split, Icons.split),
        (ViewMode.Agent, Icons.sparkle)
      ).map((m, icon) =>
        button(
          tpe           -> "button",
          cls           -> "uni-tab",
          ariaPressed   -> mode.map(c => (c == m).toString),
          data("state") ->
            mode.map(c =>
              if c == m then
                "active"
              else
                "inactive"
            ),
          onclick -> { () =>
            mode := m
          },
          icon,
          m.label
        )
      )
    ),
    Tooltip(
      "Inspect: hover the preview to find each part's code",
      trigger = button(
        cls ->
          inspecting.map(on =>
            if on then
              "inspect-toggle is-on"
            else
              "inspect-toggle"
          ),
        ariaLabel   -> "Inspect",
        ariaPressed -> inspecting.map(_.toString),
        onclick     -> { () =>
          inspecting.update(!_)
          link.clear(LinkSource.Preview)
        },
        Icons.crosshair,
        span("Inspect")
      )
    ),
    when(embed, story.map(s => div(cls -> "story-toolbar__title", s.title))),
    div(cls -> "story-toolbar__spacer"),
    story.map(s => chips(s)),
    when(
      embed,
      story.map(s =>
        a(
          cls       -> "story-link",
          href      -> s"index.html#/${s.id}",
          target    -> "_blank",
          titleAttr -> "Open in the gallery",
          Icons.external
        )
      )
    )
  )

end StoryView

object StoryView:
  /** Below this card width, the code or agent view goes under the preview. */
  val stackBelow: Double = 760

  /** The largest share of the card the side pane takes, so the preview stays visible. */
  val maxShare: Double = 0.6
  val minPane: Int     = 280

/** The controls of a playground story, in a bar above the preview. */
class PropsPanel(pg: Playground[?]) extends RxElement:
  override def render: RxElement = renderOf(pg)

  private def renderOf[P](p: Playground[P]): RxElement = div(
    cls -> "props-panel",
    span(cls -> "props-title", Icons.sliders, "Props"),
    p.controls.map(c => div(cls -> "props-control", c.editor(p.props))),
    div(cls -> "props-spacer"),
    Button("Reset").ghost.small.onClick(() => p.reset())
  )
