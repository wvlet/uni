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
  private var root: Option[dom.Element] = None

  override def onMount(node: Any): Unit =
    root = Some(node.asInstanceOf[dom.Element])
    // Opened at one example (#/button/disabled): bring it into view
    focus.foreach { s =>
      dom
        .window
        .setTimeout(
          () =>
            root
              .flatMap(r => Option(r.querySelector(s"[data-example='${s.id}']")))
              .foreach(_.scrollIntoView(true)),
          50
        )
    }

  override def render: RxElement =
    val group = Pages.groupOf(page)
    div(
      cls -> "page",
      div(
        cls -> "story-hero",
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
            div(
              cls -> "page-toc",
              page
                .examples
                .map(s =>
                  a(
                    cls  -> "page-toc__link",
                    href -> s"#/${s.id}",
                    s.title,
                    when(s.playground.isDefined, span(cls -> "story-badge", "Playground"))
                  )
                ) ++
                Seq(
                  a(
                    cls     -> "page-toc__link page-toc__link--ref",
                    href    -> s"#/${page.id}",
                    onclick -> { (e: dom.Event) =>
                      scrollTo(e, "parts")
                    },
                    "Parts"
                  ),
                  a(
                    cls     -> "page-toc__link page-toc__link--ref",
                    href    -> s"#/${page.id}",
                    onclick -> { (e: dom.Event) =>
                      scrollTo(e, "api")
                    },
                    "API"
                  ),
                  a(
                    cls     -> "page-toc__link page-toc__link--ref",
                    href    -> s"#/${page.id}",
                    onclick -> { (e: dom.Event) =>
                      scrollTo(e, "a11y")
                    },
                    "Accessibility"
                  )
                )
            ),
            div(cls -> "story-meta__spacer"),
            group.map(g =>
              a(
                cls    -> "story-link",
                href   -> s"https://wvlet.org/uni/dom/${g.docs}",
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
            div(
              cls -> "example-head",
              h2(cls -> "example-title", s.title),
              p(cls  -> "example-hint", s.summary)
            ),
            StoryView(Rx.const(s), Rx.variable(defaultMode), embed = false)
          )
        ),
      section(
        cls -> "ref-section",
        id  -> "parts",
        h2(cls -> "ref-title", "Parts"),
        PartsTable(() => root)
      ),
      section(cls -> "ref-section", id -> "api", h2(cls -> "ref-title", "API"), ApiTable(page.api)),
      section(
        cls -> "ref-section",
        id  -> "a11y",
        h2(cls -> "ref-title", "Accessibility"),
        A11yPanel(page, () => root)
      )
    )

  end render

  private def scrollTo(e: dom.Event, sectionId: String): Unit =
    e.preventDefault()
    root.flatMap(r => Option(r.querySelector(s"#${sectionId}"))).foreach(_.scrollIntoView(true))

end PageView

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
  * keyboard check presses keys, so a dialog it checks closes.
  */
class A11yPanel(page: ComponentPage, root: () => Option[dom.Element]) extends RxElement:
  import A11yPanel.Result

  private val results  = Rx.variable(Seq.empty[Result])
  private val running  = Rx.variable(false)
  private val keyboard = Rx.variable(false)

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
      dom.window.setTimeout(() => run(), 400)

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
