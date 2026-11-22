package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

/**
  * The header of a gallery page: overline, title, summary, and links to the page's sections. It
  * sticks to the top of the gallery; after a short scroll it folds to the title and the links over
  * a blurred background, and unfolds back at the top. The link of the section in view is marked
  * (`aria-current`).
  *
  * The page marks each section the links name with `data-section`, as a sibling subtree of this
  * header, and gives it `scroll-margin-top: var(--page-head)` so a scrolled-to section clears the
  * folded header.
  */
class PageHeader(
    title: String,
    summary: String,
    links: Seq[PageHeader.Link],
    overline: Seq[String] = Nil,
    trailing: Seq[DomNode] = Nil,
    initial: Option[String] = None
) extends RxElement:
  private var hero: Option[dom.Element]          = None
  private var scroller: Option[dom.Element]      = None
  private var resize: Option[dom.ResizeObserver] = None
  private var frame                              = 0
  // Scrolled past the header: it stays at the top, folded to the title and the section links
  private val stuck = Rx.variable(false)
  // The section in view, highlighted in the links
  private val active = Rx.variable(initial.orElse(links.headOption.map(_.key)))

  private val onScroll: scala.scalajs.js.Function1[dom.Event, Unit] =
    (_: dom.Event) =>
      if frame == 0 then
        frame = dom
          .window
          .requestAnimationFrame(_ =>
            frame = 0
            update()
          )

  private val onTocScroll: scala.scalajs.js.Function1[dom.Event, Unit] =
    (_: dom.Event) => markClipped()

  // The page around the header, which holds the sections
  private def page: Option[dom.Element] = hero.flatMap(h =>
    Option(h.parentNode).collect { case e: dom.Element =>
      e
    }
  )

  private def sections: Seq[dom.Element] = page
    .toSeq
    .flatMap(_.querySelectorAll("[data-section]").toSeq)

  private def sectionOf(key: String): Option[dom.Element] = page.flatMap(p =>
    Option(p.querySelector(s"[data-section='${key}']"))
  )

  private def toc: Option[dom.Element] = hero.flatMap(h => Option(h.querySelector(".page-toc")))

  // Folded, the links scroll sideways on one line: fade the right edge while more links are
  // hidden there
  private def markClipped(): Unit = toc.foreach { t =>
    val clipped = t.scrollLeft + t.clientWidth < t.scrollWidth - 1
    if clipped != t.classList.contains("is-clipped") then
      t.classList.toggle("is-clipped")
  }

  // Scroll the links sideways so the active one shows
  private def reveal(): Unit =
    for
      t   <- toc
      key <- active.get
      l   <- Option(t.querySelector(s"[data-key='${key}']"))
    do
      val tr = t.getBoundingClientRect()
      val lr = l.getBoundingClientRect()
      if lr.left < tr.left then
        t.scrollLeft -= tr.left - lr.left + PageHeader.revealMargin
      else if lr.right > tr.right - PageHeader.revealMargin then
        t.scrollLeft += lr.right - tr.right + PageHeader.revealMargin
      markClipped()

  private def update(): Unit = scroller.foreach { sc =>
    val top = sc.scrollTop
    // Fold after a short scroll, unfold only back at the top, so folding can't flicker
    val fold = (!stuck.get && top > PageHeader.foldAt) || (stuck.get && top >= PageHeader.unfoldAt)
    val folded = fold != stuck.get
    if folded then
      stuck := fold
    // The header's bottom edge, or the scroller's top if the header isn't on screen
    val headBottom = math.max(
      hero.map(_.getBoundingClientRect().bottom).getOrElse(0.0),
      sc.getBoundingClientRect().top
    )
    val all     = sections
    val atEnd   = top + sc.clientHeight >= sc.scrollHeight - 2
    val current =
      if atEnd then
        all.lastOption
      else
        all
          .takeWhile(_.getBoundingClientRect().top <= headBottom + PageHeader.spyOffset)
          .lastOption
          .orElse(all.headOption)
    val key   = current.map(_.getAttribute("data-section"))
    val moved = key != active.get
    if moved then
      active := key
    if moved || folded then
      reveal()
  }

  override def onMount(node: Any): Unit =
    val el = node.asInstanceOf[dom.Element]
    hero = Some(el)
    scroller = Option(el.closest(".gallery-main"))
    scroller.foreach(_.addEventListener("scroll", onScroll))
    toc.foreach(_.addEventListener("scroll", onTocScroll))
    // Scrolling to a section leaves room for the folded header
    val ro = dom.ResizeObserver((_, _) =>
      if stuck.get then
        page.foreach(
          _.asInstanceOf[dom.html.Element]
            .style
            .setProperty("--page-head", s"${el.getBoundingClientRect().height}px")
        )
    )
    ro.observe(el)
    resize = Some(ro)

  override def beforeUnmount: Unit =
    scroller.foreach(_.removeEventListener("scroll", onScroll))
    toc.foreach(_.removeEventListener("scroll", onTocScroll))
    resize.foreach(_.disconnect())
    resize = None
    if frame != 0 then
      dom.window.cancelAnimationFrame(frame)

  // A link scrolls to its section even when the URL already names it, and keeps the URL on the
  // section shown without a hashchange
  private def tocLink(l: PageHeader.Link) = a(
    cls -> (
      if l.ref then
        "page-toc__link page-toc__link--ref"
      else
        "page-toc__link"
    ),
    href        -> s"#/${l.hash}",
    data("key") -> l.key,
    ariaCurrent ->
      active.map(a =>
        if a.contains(l.key) then
          "true"
        else
          "false"
      ),
    onclick -> { (e: dom.Event) =>
      e.preventDefault()
      sectionOf(l.key).foreach(_.scrollIntoView(true))
      active := Some(l.key)
      reveal()
      dom.window.history.replaceState(null, "", s"#/${l.hash}")
    },
    l.label,
    l.badge.map(b => span(cls -> "story-badge", b))
  )

  override def render: RxElement = div(
    cls ->
      stuck.map(on =>
        if on then
          "story-hero is-stuck"
        else
          "story-hero"
      ),
    div(
      cls -> "story-hero__inner",
      when(
        overline.nonEmpty,
        div(
          cls -> "story-overline",
          overline
            .zipWithIndex
            .flatMap((o, i) =>
              if i == 0 then
                Seq(o: DomNode)
              else
                Seq(span(cls -> "story-overline__sep", "/"), o: DomNode)
            )
        )
      ),
      h1(cls -> "story-title", title),
      p(cls  -> "story-summary", summary),
      div(
        cls -> "story-meta",
        nav(cls -> "page-toc", ariaLabel -> "Sections", links.map(tocLink)),
        div(cls -> "story-meta__spacer"),
        trailing
      )
    )
  )

end PageHeader

object PageHeader:
  /**
    * A link to the page's section marked `data-section=key`. `hash` is the URL kept while it is
    * shown; `ref` draws it dashed, for reference sections after the examples.
    */
  case class Link(
      key: String,
      label: String,
      hash: String,
      ref: Boolean = false,
      badge: Option[String] = None
  )

  /** Scrolled this far, the header folds; it unfolds only near the top again. */
  val foldAt: Double   = 64
  val unfoldAt: Double = 8

  /** Room kept beside the active link when the folded links scroll sideways to it. */
  val revealMargin: Double = 32

  /** A section counts as shown once its top is this close under the header. */
  val spyOffset: Double = 48
