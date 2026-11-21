package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*
import wvlet.uni.ui.base.{Align, Side}

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
  * The gallery, built with the components it shows, in the style of uni-harness: an icon rail of
  * the catalog sections, a `Tree` of stories in a resizable `FoldablePane`, and the story with its
  * preview and code. ⌘K opens a `CommandPalette` over the stories, and a `Menu` on the rail sets
  * the `ColorScheme`. In embed mode (`?embed`, for an iframe in the docs) it shows only the story
  * card.
  */
class Gallery(
    current: RxVar[Story],
    scheme: RxVar[ColorScheme],
    mode: RxVar[ViewMode],
    embed: Boolean
) extends RxElement:
  private val paletteOpen   = Rx.variable(false)
  private val themeOpen     = Rx.variable(false)
  private val sidebarOpen   = Rx.variable(true)
  private val sidebarWidth  = Rx.variable(248)
  private val selectedKey   = Rx.variable(Option(current.get.id))
  private var subscriptions = Cancelable.empty

  override def onMount(node: Any): Unit =
    val keys = Keyboard.bindAll(
      "meta+k" -> (() => paletteOpen := true),
      "ctrl+k" -> (() => paletteOpen := true),
      "meta+b" -> (() => sidebarOpen.update(!_)),
      "ctrl+b" -> (() => sidebarOpen.update(!_))
    )
    // Keep the sidebar's highlight on the story shown, also after a back or forward navigation
    val sync = current.run(s => selectedKey := Some(s.id))
    subscriptions = Cancelable.merge(keys, sync)

  override def beforeUnmount: Unit = subscriptions.cancel

  override def render: RxElement =
    if embed then
      div(cls -> "gallery gallery--embed", StoryView(current, mode, embed = true))
    else
      div(
        cls -> "gallery",
        rail,
        FoldablePane(sidebarOpen, width = sidebarWidth, edge = Edge.Right, content = sidebar)
          .withSplitter(
            Splitter(
              sidebarWidth,
              SizeRange(min = 200, default = 248, max = 400),
              Edge.Right,
              "Resize sidebar"
            )
          ),
        main(cls -> "gallery-main aurora", StoryView(current, mode, embed = false)),
        CommandPalette(paletteOpen, Rx.const(commands))
          .withPlaceholder("Search components")
          .withLabel("Search components")
      )

  private def railButton(g: StoryGroup): RxElement =
    val active = current.map(_.group == g.id)
    Tooltip(
      g.title,
      trigger = button(
        ariaLabel -> g.title,
        cls       ->
          active.map(on =>
            if on then
              "rail-button is-active"
            else
              "rail-button"
          ),
        onclick -> { () =>
          sidebarOpen := true
          g.stories.headOption.foreach(Gallery.navigate)
        },
        Icons.forGroup(g.id)
      )
    ).withSide(Side.Right)

  private def rail: RxElement = nav(
    cls -> "gallery-rail",
    a(
      cls       -> "rail-logo",
      href      -> "https://wvlet.org/uni/dom/ui",
      ariaLabel -> "Uni UI docs",
      Icons.logo
    ),
    div(cls -> "rail-group", Stories.groups.map(railButton)),
    div(cls -> "rail-spacer"),
    Tooltip(
      "Theme",
      trigger = Menu(
        themeOpen,
        trigger = button(
          ariaLabel -> "Theme",
          cls       -> "rail-button",
          scheme.map {
            case ColorScheme.System =>
              Icons.monitor
            case ColorScheme.Light =>
              Icons.sun
            case ColorScheme.Dark =>
              Icons.moon
          }
        ),
        entries = ColorScheme
          .values
          .toSeq
          .map(s => MenuItem(s.label).withChecked(scheme.map(_ == s)).onSelect(() => scheme := s))
      ).withSide(Side.Right).withAlign(Align.End)
    ).withSide(Side.Right),
    Tooltip(
      "GitHub",
      trigger = a(
        cls       -> "rail-button",
        href      -> "https://github.com/wvlet/uni/tree/main/examples/ui-gallery",
        target    -> "_blank",
        ariaLabel -> "GitHub",
        Icons.github
      )
    ).withSide(Side.Right)
  )

  private def sidebar: RxElement = div(
    cls -> "gallery-sidebar",
    div(
      cls -> "sidebar-head",
      div(
        div(cls -> "sidebar-title", "Uni UI Gallery"),
        div(cls -> "sidebar-sub", s"${Stories.all.size} stories · live code")
      ),
      Tooltip(
        "Hide sidebar (⌘B)",
        trigger = IconButton("Hide sidebar", Icons.panelLeft).onClick(() => sidebarOpen := false)
      )
    ),
    button(
      cls     -> "sidebar-search",
      onclick -> { () =>
        paletteOpen := true
      },
      Icons.search,
      span("Search components"),
      Kbd("⌘K")
    ),
    div(cls -> "sidebar-overline", "Components"),
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
          span(cls -> "nav-section", Icons.forGroup(g.id), g.title)
        case NavNode.Leaf(s) =>
          span(cls -> "nav-story", s.title)
      }
    ).withLabel("Components")
      .withRowHeight(32)
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
      }(cls += "sidebar-tree")
  )

  private def commands: Seq[Command] =
    Stories
      .groups
      .flatMap { g =>
        g.stories
          .map(s =>
            Command(s.id, s.title)
              .withGroup(g.title)
              .withHint(s.summary)
              .onRun(() => Gallery.navigate(s))
          )
      } ++
      ViewMode
        .values
        .map(m =>
          Command(s"view-${m}", s"View: ${m.label}")
            .withGroup("View")
            .withKeywords("code", "preview", "split")
            .onRun(() => mode := m)
        ) ++
      ColorScheme
        .values
        .map(s =>
          Command(s"theme-${s}", s"Theme: ${s.label}")
            .withGroup("Appearance")
            .withKeywords("dark", "light", "color scheme")
            .onRun(() => scheme := s)
        )

end Gallery

object Gallery:
  /** Show `story` by setting the URL hash, so back and forward work and the URL can be shared. */
  def navigate(story: Story): Unit = dom.window.location.hash = s"#/${story.id}"

  /** The story named by a hash such as `#/controls/buttons`. */
  def storyOf(hash: String): Option[Story] = Stories.find(hash.stripPrefix("#").stripPrefix("/"))
