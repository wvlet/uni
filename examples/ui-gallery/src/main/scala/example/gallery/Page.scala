package example.gallery

import example.gallery.stories.*
import wvlet.uni.dom.testing.AriaPattern

/**
  * A WAI-ARIA APG pattern a page's component follows, the element to check (a CSS selector looked
  * up in the page and in overlays at the end of `<body>`), and how to show that element when it is
  * hidden, such as "Open the menu first".
  */
case class A11yCheck(pattern: AriaPattern, selector: String, howTo: String = "")

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
    // A guide page, such as Get started: its own sections instead of Parts, API, and Accessibility
    guide: Boolean = false
):
  def example(name: String): Option[Story] = examples.find(_.id == s"${id}/${name}")

/** A sidebar section: the pages of one catalog page of the docs. */
case class PageGroup(id: String, title: String, docs: String, pages: Seq[ComponentPage])

/** Where the gallery is: a page, and optionally one of its examples. */
case class PageRoute(page: ComponentPage, example: Option[Story]):
  def hash: String = example.map(e => s"#/${e.id}").getOrElse(s"#/${page.id}")

object Pages:
  val groups: Seq[PageGroup] = Seq(
    PageGroup("start", "Start here", "", StartStories.pages),
    PageGroup("controls", "Controls", "controls", ControlStories.pages),
    PageGroup("overlays", "Overlays", "overlays", OverlayStories.pages),
    PageGroup("collections", "Collections", "collections", CollectionStories.pages),
    PageGroup("layout", "Layout & Dock", "layout", LayoutStories.pages)
  )

  val all: Seq[ComponentPage]        = groups.flatMap(_.pages)
  val components: Seq[ComponentPage] = all.filterNot(_.guide)
  val stories: Seq[Story]            = all.flatMap(_.examples)

  def find(id: String): Option[ComponentPage]         = all.find(_.id == id)
  def story(id: String): Option[Story]                = stories.find(_.id == id)
  def groupOf(page: ComponentPage): Option[PageGroup] = groups.find(_.pages.contains(page))
  def pageOf(story: Story): Option[ComponentPage]     = all.find(_.examples.contains(story))

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
      .orElse(find(id).map(p => PageRoute(p, None)))

  /** The page a URL without a hash opens: Get started. */
  def first: PageRoute = PageRoute(all.head, None)

end Pages
