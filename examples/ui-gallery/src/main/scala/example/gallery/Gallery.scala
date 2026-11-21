package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*
import wvlet.uni.ui.base.{Align, Side}

import scala.language.implicitConversions

/** A sidebar row: a catalog section, or one component page in it. */
enum NavNode:
  case Section(group: PageGroup)
  case Leaf(page: GalleryPage)

  def key: String =
    this match
      case Section(g) =>
        s"group:${g.id}"
      case Leaf(p) =>
        p.id

/**
  * The gallery, built with the components it shows, in the style of uni-harness: an icon rail of
  * the catalog sections, a `Tree` of component pages in a resizable `FoldablePane`, and the page
  * with its examples. ⌘K opens a `CommandPalette` over the pages and examples, and a `Menu` on the
  * rail sets the `ColorScheme`. In embed mode (`?embed`, for an iframe in the docs) it shows one
  * example's card.
  */
class Gallery(
    current: RxVar[PageRoute],
    scheme: RxVar[ColorScheme],
    mode: RxVar[ViewMode],
    embed: Boolean
) extends RxElement:
  private val paletteOpen = Rx.variable(false)
  private val themeOpen   = Rx.variable(false)
  // On a phone-sized window the sidebar starts folded and floats over the story when opened
  private def compact: Boolean = dom.window.innerWidth < Gallery.compactBelow
  private val sidebarOpen      = Rx.variable(!compact)
  private val sidebarWidth     = Rx.variable(248)
  private val selectedKey      = Rx.variable(Option(current.get.page.id))
  // In embed mode, a guide page replaces the example card; the card stays mounted across examples
  private val embedGuide    = Rx.variable(Gallery.guideOf(current.get))
  private var subscriptions = Cancelable.empty

  override def onMount(node: Any): Unit =
    val keys = Keyboard.bindAll(
      "meta+k" -> (() => paletteOpen := true),
      "ctrl+k" -> (() => paletteOpen := true),
      "meta+b" -> (() => sidebarOpen.update(!_)),
      "ctrl+b" -> (() => sidebarOpen.update(!_))
    )
    // Keep the sidebar's highlight on the story shown, also after a back or forward navigation
    val sync = current.run { r =>
      selectedKey := Some(r.page.id)
      val guide = Gallery.guideOf(r)
      if guide != embedGuide.get then
        embedGuide := guide
    }
    subscriptions = Cancelable.merge(keys, sync)

  override def beforeUnmount: Unit = subscriptions.cancel

  override def render: RxElement =
    if embed then
      div(
        cls -> "gallery gallery--embed",
        embedGuide.map {
          case Some(g) =>
            g.view()
          case None =>
            StoryView(current.map(_.story).filter(_.isDefined).map(_.get), mode, embed = true)
        }
      )
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
        main(
          cls -> "gallery-main aurora",
          // A new page for a new component; moving to an example on the same page only scrolls
          current.map(r =>
            r.page match
              case c: ComponentPage =>
                PageView(c, r.example, mode.get)
              case g: GuidePage =>
                g.view()
          )
        ),
        CommandPalette(paletteOpen, Rx.const(commands))
          .withPlaceholder("Search components and examples")
          .withLabel("Search components")
      )

  private def railButton(g: PageGroup): RxElement =
    val active = current.map(r => g.pages.contains(r.page))
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
          // On a phone this opens the floating story list; picking a story closes it
          sidebarOpen := true
          g.pages.headOption.foreach(Gallery.navigateTo)
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
    div(cls -> "rail-group", Pages.groups.map(railButton)),
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
        div(
          cls -> "sidebar-sub",
          s"${Pages.all.size} components · ${Pages.stories.size} live examples"
        )
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
    div(cls -> "sidebar-overline", "Catalog"),
    Tree(
      Rx.const(Pages.groups.map(NavNode.Section(_))),
      children = {
        case NavNode.Section(g) =>
          g.pages.map(NavNode.Leaf(_))
        case NavNode.Leaf(_) =>
          Seq.empty
      },
      key = _.key,
      render = {
        case NavNode.Section(g) =>
          span(cls -> "nav-section", Icons.forGroup(g.id), g.title)
        case NavNode.Leaf(c: ComponentPage) =>
          span(cls -> "nav-story", c.name, span(cls -> "nav-count", c.examples.size.toString))
        case NavNode.Leaf(p) =>
          span(cls -> "nav-story", p.name)
      }
    ).withLabel("Catalog")
      .withRowHeight(32)
      .withExpanded(Rx.variable(Pages.groups.map(g => s"group:${g.id}").toSet))
      .withSelected(selectedKey)
      .withTypeahead {
        case NavNode.Section(g) =>
          g.title
        case NavNode.Leaf(p) =>
          p.name
      }
      .onSelect {
        case NavNode.Leaf(p) =>
          Gallery.navigateTo(p)
          if compact then
            sidebarOpen := false
        case NavNode.Section(_) =>
      }(cls += "sidebar-tree")
  )

  private def examplesOf(p: GalleryPage): Seq[Story] =
    p match
      case c: ComponentPage =>
        c.examples
      case _ =>
        Nil

  private def commands: Seq[Command] =
    Pages
      .groups
      .flatMap { g =>
        g.pages
          .flatMap(p =>
            Command(p.id, p.name)
              .withGroup(g.title)
              .withHint(p.summary)
              .onRun(() => Gallery.navigateTo(p)) +:
              examplesOf(p).map(s =>
                Command(s.id, s"${p.name}: ${s.title}")
                  .withGroup(g.title)
                  .withHint(s.summary)
                  .withKeywords(p.name)
                  .onRun(() => Gallery.navigate(s))
              )
          )
      } ++
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
  /**
    * Below this window width (matching `@media (max-width: 760px)` in gallery.css), the sidebar
    * floats.
    */
  val compactBelow: Int = 760

  /** Show `story` by setting the URL hash, so back and forward work and the URL can be shared. */
  def navigate(story: Story): Unit = dom.window.location.hash = s"#/${story.id}"

  /** Show a component's page. */
  def guideOf(r: PageRoute): Option[GuidePage] =
    r.page match
      case g: GuidePage =>
        Some(g)
      case _ =>
        None

  def navigateTo(page: GalleryPage): Unit = dom.window.location.hash = s"#/${page.id}"
