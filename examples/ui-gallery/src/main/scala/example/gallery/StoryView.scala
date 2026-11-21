package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.{SizeRange, StorageCodec}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*

import scala.language.implicitConversions

/** How a story shows its preview and its code. */
enum ViewMode(val label: String) derives StorageCodec:
  case Preview extends ViewMode("Preview")
  case Code    extends ViewMode("Code")
  case Split   extends ViewMode("Split")

object ViewMode:
  def parse(s: String): Option[ViewMode] = values.find(_.toString.equalsIgnoreCase(s))

/**
  * The uni-ui components a story's code uses, and how to find each one on screen. Hovering a chip
  * outlines the component in the preview and marks the code lines that create it.
  */
object Spotlight:
  /** Component name → the CSS selector of its root element. */
  val selectors: Seq[(String, String)] = Seq(
    "IconButton"     -> ".uni-button--icon",
    "Button"         -> ".uni-button",
    "Input"          -> ".uni-input",
    "Textarea"       -> ".uni-textarea",
    "TabList"        -> ".uni-tabs",
    "TabPanel"       -> ".uni-tab-panel",
    "Badge"          -> ".uni-badge",
    "Kbd"            -> ".uni-kbd",
    "Popover"        -> ".uni-popover-anchor, .uni-popover",
    "Tooltip"        -> ".uni-tooltip",
    "Menu"           -> ".uni-menu, [aria-haspopup='menu']",
    "Dialog"         -> ".uni-dialog",
    "AlertDialog"    -> ".uni-dialog",
    "Select"         -> ".uni-select",
    "VirtualList"    -> ".uni-virtual-list",
    "Tree"           -> ".uni-tree",
    "DataTable"      -> ".uni-table",
    "CommandPalette" -> ".uni-palette",
    "FoldablePane"   -> ".uni-pane",
    "Splitter"       -> ".uni-splitter",
    "StatusBar"      -> ".uni-statusbar",
    "Dock"           -> ".uni-dock"
  )

  /** The components `code` calls, in the order they first appear. */
  def usedIn(code: String): Seq[String] = selectors
    .map(_._1)
    .flatMap(name => s"\\b${name}\\(".r.findFirstMatchIn(code).map(m => (m.start, name)))
    .sortBy(_._1)
    .map(_._2)

  def selectorOf(name: String): Option[String] = selectors.find(_._1 == name).map(_._2)

  /** Whether a line of code creates `name`. */
  def creates(line: String, name: String): Boolean = s"\\b${name}\\(".r.findFirstIn(line).isDefined

end Spotlight

/**
  * One story: a header with its title and the components it uses, then a card that shows the live
  * preview and the code, as tabs or side by side.
  */
class StoryView(story: Rx[Story], mode: RxVar[ViewMode], embed: Boolean) extends RxElement:
  private val spot                      = Rx.variable(Option.empty[String])
  private val codeWidth                 = Rx.variable(520)
  private var root: Option[dom.Element] = None
  private var subscription              = Cancelable.empty

  // Outline the spotlighted component in the preview
  private def outline(name: Option[String]): Unit = root
    .flatMap(r => Option(r.querySelector(".story-stage")))
    .foreach { stage =>
      val old = stage.querySelectorAll(".is-spotlit")
      (0 until old.length).foreach(i => old(i).classList.remove("is-spotlit"))
      name
        .flatMap(Spotlight.selectorOf)
        .foreach { sel =>
          val found = stage.querySelectorAll(sel)
          (0 until found.length).foreach(i => found(i).classList.add("is-spotlit"))
        }
    }

  override def onMount(node: Any): Unit =
    root = Some(node.asInstanceOf[dom.Element])
    subscription = spot.run(outline)

  override def beforeUnmount: Unit = subscription.cancel

  override def render: RxElement = div(
    cls -> "story",
    when(!embed, hero),
    div(
      cls -> "story-card",
      toolbar,
      div(
        cls          -> "story-body",
        data("view") -> mode.map(_.toString.toLowerCase),
        div(
          cls -> "story-preview",
          story.map { s =>
            spot := None
            div(cls -> "story-stage", s.render())
          }
        ),
        mode.map(m =>
          when(m == ViewMode.Code, div(cls -> "story-code-full", CodeView(story, spot)))
        ),
        FoldablePane(
          mode.map(_ == ViewMode.Split),
          width = codeWidth,
          edge = Edge.Left,
          content = CodeView(story, spot)
        ).withSplitter(
          Splitter(
            codeWidth,
            SizeRange(min = 320, default = 520, max = 960),
            Edge.Left,
            "Resize code"
          )
        )
      )
    )
  )

  private def hero: RxElement = div(
    cls -> "story-hero",
    story.map { s =>
      val group = Stories.groupOf(s)
      div(
        cls -> "story-hero__inner",
        div(
          cls -> "story-overline",
          group.map(_.title).getOrElse(""),
          span(cls -> "story-overline__sep", "/"),
          s.title
        ),
        h1(cls -> "story-title", s.title),
        p(cls  -> "story-summary", s.summary),
        div(
          cls -> "story-meta",
          chips(s),
          div(cls -> "story-meta__spacer"),
          group.map(g =>
            a(
              cls    -> "story-link",
              href   -> s"https://wvlet.org/uni/dom/${g.docs}",
              target -> "_blank",
              Icons.book,
              "Docs"
            )
          ),
          s.source
            .map(src =>
              a(
                cls    -> "story-link",
                href   -> src.githubUrl,
                target -> "_blank",
                Icons.github,
                "Source"
              )
            )
        )
      )
    }
  )

  private def chips(s: Story): RxElement = div(
    cls -> "story-chips",
    s.source
      .toSeq
      .flatMap(src => Spotlight.usedIn(src.code))
      .map { name =>
        button(
          cls ->
            spot.map(sp =>
              if sp.contains(name) then
                "chip is-active"
              else
                "chip"
            ),
          titleAttr    -> s"Highlight ${name} in the preview and the code",
          onmouseenter -> { () =>
            spot := Some(name)
          },
          onmouseleave -> { () =>
            spot := None
          },
          onfocus -> { () =>
            spot := Some(name)
          },
          onblur -> { () =>
            spot := None
          },
          name
        )
      }
  )

  private def toolbar: RxElement = div(
    cls -> "story-toolbar",
    TabList(
      mode,
      label = "View",
      tabs = Seq(
        Tab(ViewMode.Preview, Icons.eye, "Preview"),
        Tab(ViewMode.Code, Icons.code, "Code"),
        Tab(ViewMode.Split, Icons.split, "Split")
      )
    )(cls += "view-switch"),
    when(embed, story.map(s => div(cls -> "story-toolbar__title", s.title))),
    div(cls -> "story-toolbar__spacer"),
    when(embed, story.map(s => chips(s))),
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
