package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.{SizeRange, Storage}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*
import wvlet.uni.ui.base.Align

import scala.language.implicitConversions

/** A sidebar row: a catalog section, or one story in it. */
enum NavNode:
  case Section(group: StoryGroup)
  case Leaf(story: Story)

  def key: String =
    this match
      case Section(g) =>
        g.id
      case Leaf(s) =>
        s.id

/**
  * The gallery, built with the components it shows: a `Tree` of stories in a `FoldablePane`, a
  * `Dock` that holds the live preview and its code, a `CommandPalette` (⌘K) to jump to a story, and
  * a `Menu` for the color scheme. In embed mode (`?embed`, for an iframe in the docs) it shows only
  * the dock.
  */
class Gallery(current: RxVar[Story], scheme: RxVar[ColorScheme], embed: Boolean) extends RxElement:
  private val paletteOpen   = Rx.variable(false)
  private val themeOpen     = Rx.variable(false)
  private val sidebarOpen   = Rx.variable(true)
  private val sidebarWidth  = Rx.variable(240)
  private val selectedKey   = Rx.variable(Option(current.get.id))
  private var subscriptions = Cancelable.empty

  private val defaultLayout = DockLayout(
    DockNode.Split(
      SplitDirection.Column,
      Seq(DockNode.group("preview"), DockNode.group("code")),
      Seq(0.55, 0.45)
    )
  )

  // The full gallery remembers how the user arranged the dock. Embedded frames don't, so one
  // docs page's frames don't change the others.
  private val layout: RxVar[DockLayout] =
    if embed then
      Rx.variable(defaultLayout)
    else
      Storage.local("uni-gallery.dock", defaultLayout)

  override def onMount(node: Any): Unit =
    val keys = Keyboard.bindAll(
      "meta+k" -> (() => paletteOpen := true),
      "ctrl+k" -> (() => paletteOpen := true)
    )
    // Keep the sidebar's highlight on the story shown, also after a back or forward navigation
    val sync = current.run(s => selectedKey := Some(s.id))
    subscriptions = Cancelable.merge(keys, sync)

  override def beforeUnmount: Unit = subscriptions.cancel

  override def render: RxElement = div(
    cls -> (
      if embed then
        "gallery gallery--embed"
      else
        "gallery"
    ),
    when(!embed, topBar),
    div(
      cls -> "gallery-body",
      when(
        !embed,
        FoldablePane(sidebarOpen, width = sidebarWidth, edge = Edge.Right, content = sidebar)
          .withSplitter(
            Splitter(
              sidebarWidth,
              SizeRange(min = 180, default = 240, max = 400),
              Edge.Right,
              "Resize sidebar"
            )
          )
      ),
      main(cls -> "gallery-main", storyHeader, storyDock)
    ),
    CommandPalette(paletteOpen, Rx.const(commands))
      .withPlaceholder("Search components")
      .withLabel("Search components")
  )

  private def topBar: RxElement = header(
    cls -> "gallery-header",
    IconButton("Toggle sidebar", span(cls -> "gallery-icon", "☰")).onClick(() =>
      sidebarOpen.update(!_)
    ),
    a(cls   -> "gallery-brand", href -> "https://wvlet.org/uni/dom/ui", "Uni UI Gallery"),
    div(cls -> "gallery-spacer"),
    Button("Search ", Kbd("⌘K")).ghost.onClick(() => paletteOpen := true),
    Menu(
      themeOpen,
      trigger = Button("Theme").ghost,
      entries = ColorScheme
        .values
        .toSeq
        .map(s => MenuItem(s.label).withChecked(scheme.map(_ == s)).onSelect(() => scheme := s))
    ).withAlign(Align.End),
    a(
      cls    -> "gallery-link",
      href   -> "https://github.com/wvlet/uni/tree/main/examples/ui-gallery",
      target -> "_blank",
      "GitHub"
    )
  )

  private def sidebar: RxElement = div(
    cls -> "gallery-sidebar",
    Tree(
      Rx.const(Stories.groups.map(NavNode.Section(_))),
      children = {
        case NavNode.Section(g) =>
          g.stories.map(NavNode.Leaf(_))
        case NavNode.Leaf(_) =>
          Seq.empty
      },
      key = _.key,
      render = {
        case NavNode.Section(g) =>
          span(cls -> "nav-section", g.title)
        case NavNode.Leaf(s) =>
          span(cls -> "nav-story", s.title)
      }
    ).withLabel("Components")
      .withExpanded(Rx.variable(Stories.groups.map(_.id).toSet))
      .withSelected(selectedKey)
      .withTypeahead {
        case NavNode.Section(g) =>
          g.title
        case NavNode.Leaf(s) =>
          s.title
      }
      .onSelect {
        case NavNode.Leaf(s) =>
          Gallery.navigate(s)
        case NavNode.Section(_) =>
      }(styleAttr += "height: 100%")
  )

  private def storyHeader: RxElement = div(
    cls -> "story-header",
    current.map { s =>
      div(
        h1(cls -> "story-title", s.title),
        p(cls  -> "story-summary", s.summary),
        when(
          !embed,
          Stories
            .groupOf(s)
            .map(g =>
              a(
                cls  -> "story-docs",
                href -> s"https://wvlet.org/uni/dom/${g.docs}",
                s"${g.title} docs →"
              )
            )
        )
      )
    }
  )

  private def storyDock: RxElement = Dock(
    layout,
    label = "Story",
    panels = Seq(
      DockPanel(
        "preview",
        title = "Preview",
        // A fresh instance for each story, so every demo starts from its initial state
        content = div(
          cls -> "story-canvas",
          current.map(s => div(cls -> "story-stage", s.render()))
        )
      ).withClosable(false),
      DockPanel("code", title = "Code", content = CodeView(current)).withClosable(false)
    )
  )

  private def commands: Seq[Command] = Stories
    .groups
    .flatMap { g =>
      g.stories
        .map(s =>
          Command(s.id, s.title)
            .withGroup(g.title)
            .withHint(s.summary)
            .onRun(() => Gallery.navigate(s))
        )
    }

end Gallery

object Gallery:
  /** Show `story` by setting the URL hash, so back and forward work and the URL can be shared. */
  def navigate(story: Story): Unit = dom.window.location.hash = s"#/${story.id}"

  /** The story named by a hash such as `#/controls/buttons`. */
  def storyOf(hash: String): Option[Story] = Stories.find(hash.stripPrefix("#").stripPrefix("/"))
