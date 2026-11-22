package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.{AriaViolation, checkPattern}
import wvlet.uni.rx.{Cancelable, Rx, RxVar}
import wvlet.uni.ui.*

import scala.collection.mutable
import scala.language.implicitConversions

/**
  * One component's page: a header with its examples listed, a card per example (preview, code,
  * agent view, and event log), then three sections read from the running page or the code: the
  * parts and states the component renders, its API, and a live WAI-ARIA check.
  */
class PageView(page: ComponentPage, focus: Option[Story], defaultMode: ViewMode) extends RxElement:
  private var root: Option[dom.Element]          = None
  private var scroller: Option[dom.Element]      = None
  private var resize: Option[dom.ResizeObserver] = None
  private var frame                              = 0
  // Scrolled past the header: it stays at the top, folded to the title and the section links
  private val stuck = Rx.variable(false)
  // The section in view, highlighted in the header's links
  private val active = Rx.variable(focus.map(_.id).orElse(page.examples.headOption.map(_.id)))

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

  private def hero: Option[dom.Element] = root.flatMap(r => Option(r.querySelector(".story-hero")))

  private def sectionOf(key: String): Option[dom.Element] = root.flatMap(r =>
    Option(r.querySelector(s"[data-section='${key}']"))
  )

  private def toc: Option[dom.Element] = root.flatMap(r => Option(r.querySelector(".page-toc")))

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
        t.scrollLeft -= tr.left - lr.left + PageView.revealMargin
      else if lr.right > tr.right - PageView.revealMargin then
        t.scrollLeft += lr.right - tr.right + PageView.revealMargin
      markClipped()

  private def update(): Unit = scroller.foreach { sc =>
    val top = sc.scrollTop
    // Fold after a short scroll, unfold only back at the top, so folding can't flicker
    val fold   = (!stuck.get && top > PageView.foldAt) || (stuck.get && top >= PageView.unfoldAt)
    val folded = fold != stuck.get
    if folded then
      stuck := fold
    // The header's bottom edge, or the scroller's top if the header isn't on screen
    val headBottom = math.max(
      hero.map(_.getBoundingClientRect().bottom).getOrElse(0.0),
      sc.getBoundingClientRect().top
    )
    val sections = root.toSeq.flatMap(_.querySelectorAll("[data-section]").toSeq)
    val atEnd    = top + sc.clientHeight >= sc.scrollHeight - 2
    val current  =
      if atEnd then
        sections.lastOption
      else
        sections
          .takeWhile(_.getBoundingClientRect().top <= headBottom + PageView.spyOffset)
          .lastOption
          .orElse(sections.headOption)
    val key   = current.map(_.getAttribute("data-section"))
    val moved = key != active.get
    if moved then
      active := key
    if moved || folded then
      reveal()
  }

  override def onMount(node: Any): Unit =
    val el = node.asInstanceOf[dom.Element]
    root = Some(el)
    scroller = Option(el.closest(".gallery-main"))
    scroller.foreach(_.addEventListener("scroll", onScroll))
    toc.foreach(_.addEventListener("scroll", onTocScroll))
    // Scrolling to a section leaves room for the folded header
    hero.foreach { h =>
      val ro = dom.ResizeObserver((_, _) =>
        if stuck.get then
          el.asInstanceOf[dom.html.Element]
            .style
            .setProperty("--page-head", s"${h.getBoundingClientRect().height}px")
      )
      ro.observe(h)
      resize = Some(ro)
    }
    // Opened at one example (#/button/disabled): bring it into view
    focus.foreach { s =>
      dom.window.setTimeout(() => sectionOf(s.id).foreach(_.scrollIntoView(true)), 50)
    }

  override def beforeUnmount: Unit =
    scroller.foreach(_.removeEventListener("scroll", onScroll))
    toc.foreach(_.removeEventListener("scroll", onTocScroll))
    resize.foreach(_.disconnect())
    resize = None
    if frame != 0 then
      dom.window.cancelAnimationFrame(frame)

  // A link in the header. It scrolls even when the URL already names its section, and keeps the
  // URL on the example shown without a hashchange
  private def tocLink(key: String, hash: String, label: String, ref: Boolean, badge: Boolean) = a(
    cls -> (
      if ref then
        "page-toc__link page-toc__link--ref"
      else
        "page-toc__link"
    ),
    href        -> s"#/${hash}",
    data("key") -> key,
    ariaCurrent ->
      active.map(a =>
        if a.contains(key) then
          "true"
        else
          "false"
      ),
    onclick -> { (e: dom.Event) =>
      e.preventDefault()
      sectionOf(key).foreach(_.scrollIntoView(true))
      active := Some(key)
      reveal()
      dom.window.history.replaceState(null, "", s"#/${hash}")
    },
    label,
    when(badge, span(cls -> "story-badge", "Playground"))
  )

  override def render: RxElement =
    val group = Pages.groupOf(page)
    div(
      cls -> "page",
      div(
        cls ->
          stuck.map(on =>
            if on then
              "story-hero is-stuck"
            else
              "story-hero"
          ),
        div(
          cls -> "story-hero__inner",
          div(
            cls -> "story-overline",
            group.map(_.title).getOrElse(""),
            span(cls -> "story-overline__sep", "/"),
            page.name
          ),
          h1(cls -> "story-title", page.name),
          p(cls  -> "story-summary", page.summary),
          div(
            cls -> "story-meta",
            nav(
              cls       -> "page-toc",
              ariaLabel -> "Sections",
              page
                .examples
                .map(s =>
                  tocLink(s.id, s.id, s.title, ref = false, badge = s.playground.isDefined)
                ) ++ (
                if !page.isComponent then
                  Nil
                else
                  PageView
                    .refSections
                    .map((key, label) => tocLink(key, page.id, label, ref = true, badge = false))
              )
            ),
            div(cls -> "story-meta__spacer"),
            group.map(g =>
              a(
                cls    -> "story-link",
                href   -> s"https://wvlet.org/uni/${g.docs}",
                target -> "_blank",
                Icons.book,
                "Docs"
              )
            )
          )
        )
      ),
      page
        .examples
        .map(s =>
          section(
            cls             -> "example-section",
            data("example") -> s.id,
            data("section") -> s.id,
            div(
              cls -> "example-head",
              h2(cls -> "example-title", s.title),
              p(cls  -> "example-hint", s.summary)
            ),
            StoryView(Rx.const(s), Rx.variable(defaultMode), embed = false)
          )
        ),
      // A topic page, such as the Rx pages, has examples only
      when(
        page.isComponent,
        Seq(
          section(
            cls             -> "ref-section",
            id              -> "parts",
            data("section") -> "parts",
            h2(cls -> "ref-title", "Parts"),
            PartsTable(() => root)
          ),
          section(
            cls             -> "ref-section",
            id              -> "api",
            data("section") -> "api",
            h2(cls -> "ref-title", "API"),
            ApiTable(page.api)
          ),
          section(
            cls             -> "ref-section",
            id              -> "a11y",
            data("section") -> "a11y",
            h2(cls -> "ref-title", "Accessibility"),
            A11yPanel(page, () => root)
          )
        )
      )
    )

  end render

end PageView

object PageView:
  /** The reference sections after the examples, by their `data-section` key. */
  val refSections: Seq[(String, String)] = Seq(
    "parts" -> "Parts",
    "api"   -> "API",
    "a11y"  -> "Accessibility"
  )

  /** Scrolled this far, the header folds; it unfolds only near the top again. */
  val foldAt: Double   = 64
  val unfoldAt: Double = 8

  /** Room kept beside the active link when the folded links scroll sideways to it. */
  val revealMargin: Double = 32

  /** A section counts as shown once its top is this close under the header. */
  val spyOffset: Double = 48

/**
  * The `data-slot` parts the page's examples render, read from the live DOM: the component that
  * owns each part, its element and role, and every `data-state` seen so far. Use the examples and
  * new states appear. Style a part with `[data-slot=...][data-state=...]`.
  */
class PartsTable(root: () => Option[dom.Element]) extends RxElement:
  import PartsTable.Part

  private val parts = Rx.variable(Seq.empty[Part])
  private val seen  = mutable.LinkedHashMap.empty[(String, String), Part]
  private var observer: Option[dom.MutationObserver] = None
  private var pending                                = 0

  private def scan(): Unit = root().foreach { r =>
    val nodes = r.querySelectorAll(".story-stage [data-slot]")
    (0 until nodes.length).foreach { i =>
      val el   = nodes(i)
      val kind = Option(el.closest("[data-src-kind]"))
        .map(_.getAttribute("data-src-kind"))
        .getOrElse("—") // a component held in a val first isn't tagged
      val slot  = el.getAttribute("data-slot")
      val state = Option(el.getAttribute("data-state")).toSet
      val key   = (kind, slot)
      val prev  = seen.get(key)
      seen(key) = Part(
        kind,
        slot,
        el.tagName.toLowerCase,
        Option(el.getAttribute("role")).orElse(prev.flatMap(_.role)),
        prev.map(_.states).getOrElse(Set.empty) ++ state
      )
    }
    parts := seen.values.toSeq.sortBy(p => (p.kind, p.slot))
  }

  override def onMount(node: Any): Unit =
    dom.window.setTimeout(() => scan(), 200)
    root().foreach { r =>
      val mo = dom.MutationObserver { (_, _) =>
        // One scan per burst of changes
        dom.window.clearTimeout(pending)
        pending = dom.window.setTimeout(() => scan(), 150)
      }
      mo.observe(
        r,
        new dom.MutationObserverInit:
          subtree = true
          childList = true
          attributes = true
          attributeFilter = scala.scalajs.js.Array("data-state", "data-slot")
      )
      observer = Some(mo)
    }

  override def beforeUnmount: Unit =
    observer.foreach(_.disconnect())
    dom.window.clearTimeout(pending)

  override def render: RxElement = div(
    cls -> "ref-body",
    p(
      cls -> "ref-note",
      "Read from the examples above as they run. Style a part with ",
      code("[data-slot=\"...\"][data-state=\"...\"]"),
      "; open menus and dialogs to see their parts."
    ),
    parts.map { ps =>
      if ps.isEmpty then
        p(cls -> "ref-empty", "No data-slot parts rendered yet.")
      else
        table(
          cls -> "ref-table",
          thead(tr(th("Component"), th("Slot"), th("Element"), th("Role"), th("States seen"))),
          tbody(
            ps.map(pt =>
              tr(
                td(code(pt.kind)),
                td(code(pt.slot)),
                td(code(s"<${pt.tag}>")),
                td(pt.role.getOrElse("")),
                td(pt.states.toSeq.sorted.map(st => span(cls -> "ref-chip", st)))
              )
            )
          )
        )
    }
  )

end PartsTable

object PartsTable:
  case class Part(
      kind: String,
      slot: String,
      tag: String,
      role: Option[String],
      states: Set[String]
  )

/** The component's API, listed at compile time from its declarations by [[ApiDoc]]. */
class ApiTable(entries: Seq[ApiEntry]) extends RxElement:
  private def kindLabel(k: String): String =
    k match
      case "create" =>
        "create"
      case "option" =>
        "option"
      case _ =>
        "method"

  override def render: RxElement = div(
    cls -> "ref-body",
    p(
      cls -> "ref-note",
      "Listed from the component's declarations and doc comments when the gallery compiles."
    ),
    entries
      .groupBy(_.component)
      .toSeq
      .sortBy((c, _) => entries.indexWhere(_.component == c))
      .map { (component, es) =>
        div(
          cls -> "api-group",
          h3(cls -> "api-component", component),
          table(
            cls -> "ref-table",
            tbody(
              es.map(e =>
                tr(
                  td(
                    cls -> "api-kind",
                    span(cls -> s"ref-chip ref-chip--${e.kind}", kindLabel(e.kind))
                  ),
                  td(code(cls -> "api-signature", e.signature)),
                  td(cls -> "api-doc", e.doc)
                )
              )
            )
          )
        )
      }
  )

end ApiTable

/**
  * Runs uni-ui's WAI-ARIA checker (`checkPattern`, the one uni-ui's own tests use) on the page's
  * live components and lists what it finds. The structure check doesn't change anything; the
  * keyboard check presses keys, so a dialog it checks closes. It checks again when a widget it
  * looks for appears, such as a menu opening, since pressing Check would close it first.
  */
class A11yPanel(page: ComponentPage, root: () => Option[dom.Element]) extends RxElement:
  import A11yPanel.Result

  private val results                                = Rx.variable(Seq.empty[Result])
  private val running                                = Rx.variable(false)
  private val keyboard                               = Rx.variable(false)
  private var observer: Option[dom.MutationObserver] = None
  private var pending                                = 0
  private var counts                                 = Seq.empty[Int]

  private def present: Seq[Int] = page
    .a11y
    .map(c => dom.document.querySelectorAll(c.selector).length)

  // Re-check when a widget appears (an open menu, a shown tooltip); keep the results when it goes
  private def recheckIfNew(): Unit =
    val now     = present
    val appears = now.zipAll(counts, 0, 0).exists((n, before) => n > before)
    counts = now
    if appears && !running.get then
      run()

  private def describe(el: dom.Element): String =
    val role  = Option(el.getAttribute("role")).map(r => s"[role=${r}]").getOrElse("")
    val label = Option(el.getAttribute("aria-label")).orElse(
      Option(el.textContent).map(_.trim.take(24))
    )
    s"<${el.tagName.toLowerCase}>${role}${label
        .filter(_.nonEmpty)
        .map(l => s" \"${l}\"")
        .getOrElse("")}"

  private def run(): Unit =
    running := true
    val checks = page
      .a11y
      .flatMap { c =>
        val all = dom.document.querySelectorAll(c.selector)
        val els = (0 until all.length).map(all(_))
        // The examples' own components, not the gallery around them
        val inPage = els.filter(el =>
          el.closest(".story-stage") != null && root().exists(_.contains(el))
        )
        // Open menus, selects, and dialogs render at the end of <body>
        val inBody = els.filter(el =>
          !root().exists(_.contains(el)) && el.closest(".gallery") == null
        )
        val found = (inPage ++ inBody).distinct.take(6)
        if found.isEmpty then
          Seq(Left(c))
        else
          found.map(el => Right((c, el)))
      }
    val out  = mutable.ArrayBuffer.empty[Result]
    var left = checks.count(_.isRight)
    checks.foreach {
      case Left(c) =>
        out += Result(c.pattern.toString, "", None, c.howTo)
      case Right((c, el)) =>
        checkPattern(el, c.pattern, keyboard = keyboard.get).run { vs =>
          out += Result(c.pattern.toString, describe(el), Some(vs), "")
          left -= 1
          if left == 0 then
            results := out.toSeq
            running := false
        }
    }
    if left == 0 then
      results := out.toSeq
      running := false

  end run

  override def onMount(node: Any): Unit =
    if page.a11y.nonEmpty then
      dom
        .window
        .setTimeout(
          () =>
            counts = present
            run()
          ,
          400
        )
      val mo = dom.MutationObserver { (_, _) =>
        dom.window.clearTimeout(pending)
        pending = dom.window.setTimeout(() => recheckIfNew(), 200)
      }
      mo.observe(
        dom.document.body,
        new dom.MutationObserverInit:
          subtree = true
          childList = true
      )
      observer = Some(mo)

  override def beforeUnmount: Unit =
    observer.foreach(_.disconnect())
    dom.window.clearTimeout(pending)

  private def resultRow(r: Result): RxElement =
    r.violations match
      case None =>
        div(
          cls -> "a11y-row a11y-row--missing",
          span(cls -> "a11y-pattern", r.pattern),
          span(cls -> "ref-note", r.howTo)
        )
      case Some(vs) if vs.isEmpty =>
        div(
          cls -> "a11y-row a11y-row--pass",
          span(cls -> "a11y-mark", "✓"),
          span(cls -> "a11y-pattern", r.pattern),
          code(r.element)
        )
      case Some(vs) =>
        div(
          cls -> "a11y-row a11y-row--fail",
          span(cls -> "a11y-mark", "✕"),
          span(cls -> "a11y-pattern", r.pattern),
          code(r.element),
          ul(vs.map(v => li(code(v.rule), " ", v.message)))
        )

  override def render: RxElement = div(
    cls -> "ref-body",
    when(page.a11yNote.nonEmpty, p(cls -> "ref-note", page.a11yNote)),
    if page.a11y.isEmpty then
      p(
        cls -> "ref-note",
        "No WAI-ARIA composite pattern applies, so there is nothing for checkPattern to check."
      )
    else
      div(
        p(
          cls -> "ref-note",
          "checkPattern checks the components below against the WAI-ARIA Authoring Practices: roles, required states, names, id references, and focus. ",
          "With keys on, it also presses the keys the pattern requires and checks where focus goes."
        ),
        div(
          cls -> "a11y-actions",
          Button(
            running.map(r =>
              if r then
                "Checking…"
              else
                "Check again"
            )
          ).small.withDisabled(running).onClick(() => run()),
          label(
            cls -> "prop-toggle",
            input(
              tpe      -> "checkbox",
              role     -> "switch",
              checked  -> keyboard,
              onchange -> { (e: dom.Event) =>
                keyboard := e.target.asInstanceOf[dom.html.Input].checked
              }
            ),
            span(cls -> "prop-toggle__track"),
            span(cls -> "prop-toggle__label", "keys too")
          )
        ),
        results.map(rs => div(cls -> "a11y-results", rs.map(resultRow)))
      )
  )

end A11yPanel

object A11yPanel:
  case class Result(
      pattern: String,
      element: String,
      violations: Option[Seq[AriaViolation]],
      howTo: String
  )
