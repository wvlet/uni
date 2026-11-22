package example.gallery

import example.gallery.stories.*
import wvlet.uni.dom.RxElement
import wvlet.uni.dom.testing.AriaPattern

/**
  * A WAI-ARIA APG pattern a page's component follows, the element to check (a CSS selector looked
  * up in the page and in overlays at the end of `<body>`), and how to show that element when it is
  * hidden, such as "Open the menu first".
  */
case class A11yCheck(pattern: AriaPattern, selector: String, howTo: String = "")

/** A page of the gallery: a component's page, or a guide page such as Colors. */
sealed trait GalleryPage:
  def id: String
  def name: String
  def summary: String

/**
  * One component's page: several examples (each a [[Story]]), the component's API read from its
  * declarations by [[ApiDoc]], and the accessibility patterns it is checked against.
  */
case class ComponentPage(
    id: String,
    name: String,
    summary: String,
    examples: Seq[Story],
    api: Seq[ApiEntry],
    a11y: Seq[A11yCheck] = Nil,
    a11yNote: String = "",
    topic: Boolean = false,
    // The docs page under https://wvlet.org/uni/ that the page links to, when not its group's
    docs: String = ""
) extends GalleryPage:
  def example(name: String): Option[Story] = examples.find(_.id == s"${id}/${name}")

  /**
    * A page about a uni-ui component, with its parts, API, and accessibility check. A topic page
    * (such as the Rx pages) has examples only.
    */
  def isComponent: Boolean = !topic

/** A page with its own view instead of examples, such as the color tokens. */
case class GuidePage(id: String, name: String, summary: String, view: () => RxElement)
    extends GalleryPage

/**
  * A sidebar section: the pages of one catalog page of the docs, at `docs` under
  * https://wvlet.org/uni/.
  */
case class PageGroup(id: String, title: String, docs: String, pages: Seq[GalleryPage]):
  def components: Seq[ComponentPage] = pages.collect { case c: ComponentPage =>
    c
  }

/** Where the gallery is: a page, and optionally one of its examples. */
case class PageRoute(page: GalleryPage, example: Option[Story]):
  def hash: String = example.map(e => s"#/${e.id}").getOrElse(s"#/${page.id}")

  /** The example an embedded card shows: the one named, else the page's first. */
  def story: Option[Story] = example.orElse(
    page match
      case c: ComponentPage =>
        c.examples.headOption
      case _ =>
        None
  )

object Pages:
  val groups: Seq[PageGroup] = Seq(
    PageGroup("start", "Start here", "", Seq(StartStories.page)),
    PageGroup("foundations", "Foundations", "dom/theming", Seq(ColorsPage.page)),
    PageGroup("controls", "Controls", "dom/controls", ControlStories.pages),
    PageGroup("overlays", "Overlays", "dom/overlays", OverlayStories.pages),
    PageGroup("collections", "Collections", "dom/collections", CollectionStories.pages),
    PageGroup("layout", "Layout & Dock", "dom/layout", LayoutStories.pages),
    PageGroup("rx", "Rx", "rx/operators", RxStories.pages)
  )

  /** Every page, guides included. */
  val pages: Seq[GalleryPage] = groups.flatMap(_.pages)

  /** The component pages, each with examples and an API. */
  val all: Seq[ComponentPage] = groups.flatMap(_.components)
  val stories: Seq[Story]     = all.flatMap(_.examples)

  def find(id: String): Option[ComponentPage]       = all.find(_.id == id)
  def findPage(id: String): Option[GalleryPage]     = pages.find(_.id == id)
  def story(id: String): Option[Story]              = stories.find(_.id == id)
  def groupOf(page: GalleryPage): Option[PageGroup] = groups.find(_.pages.contains(page))
  def pageOf(story: Story): Option[ComponentPage]   = all.find(_.examples.contains(story))

  /** Story ids of earlier gallery versions, kept so old links and docs embeds still open. */
  val aliases: Map[String, String] = Map(
    "controls/buttons"         -> "button/variants",
    "controls/button-props"    -> "button/props",
    "controls/text-fields"     -> "input/basic",
    "controls/input-props"     -> "input/props",
    "controls/tabs"            -> "tabs/basic",
    "overlays/menu"            -> "menu/basic",
    "overlays/dialog"          -> "dialog/basic",
    "overlays/select"          -> "select/basic",
    "overlays/popover-tooltip" -> "popover/basic",
    "collections/tree"         -> "tree/basic",
    "collections/data-table"   -> "data-table/basic",
    "collections/reorder"      -> "virtual-list/reorder",
    "layout/dock"              -> "dock/basic",
    "layout/foldable-pane"     -> "foldable-pane/basic"
  )

  /** The route a hash such as `#/button` or `#/button/disabled` names. */
  def route(hash: String): Option[PageRoute] =
    val path = hash.stripPrefix("#").stripPrefix("/")
    val id   = aliases.getOrElse(path, path)
    story(id)
      .flatMap(s => pageOf(s).map(p => PageRoute(p, Some(s))))
      .orElse(findPage(id).map(p => PageRoute(p, None)))

  /** The page a URL without a hash opens: Get started. */
  def first: PageRoute = PageRoute(pages.head, None)

end Pages
