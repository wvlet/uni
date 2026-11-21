package example.gallery

import example.gallery.stories.StartStories
import org.scalajs.dom
import wvlet.uni.dom.all.{div, styleAttr}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.ColorScheme

import scala.concurrent.{ExecutionContext, Promise}
import scala.language.implicitConversions

/** The pages, their examples and code, and the gallery shell, rendered into a real DOM. */
class GalleryTest extends DomTestSupport:

  private def story(id: String): Story        = Pages.story(id).get
  private def page(id: String): ComponentPage = Pages.find(id).get

  private def waitMs(ms: Int): Rx[Unit] =
    val p = Promise[Unit]()
    dom.window.setTimeout(() => p.success(()), ms)
    Rx.future(p.future)(using ExecutionContext.parasitic)

  test("every example has a // #region block for its code panel") {
    Pages.stories.filter(_.source.isEmpty).map(_.id) shouldBe Seq.empty
  }

  test("example ids are unique and start with their page's id") {
    Pages.stories.map(_.id).distinct.size shouldBe Pages.stories.size
    Pages.all.foreach(p => p.examples.foreach(s => s.id.startsWith(s"${p.id}/") shouldBe true))
    Pages.all.map(_.id).distinct.size shouldBe Pages.all.size
  }

  test("every page lists its component's API, starting with how to create it") {
    Pages
      .all
      .foreach { p =>
        (p.examples.nonEmpty && p.api.nonEmpty) shouldBe true
        p.api.head.kind shouldBe "create"
      }
    val button = page("button").api
    button.map(_.signature) shouldContain ".withLabel(l: String)"
    button.find(_.signature == ".withLabel(l: String)").get.doc shouldContain "accessible name"
    page("toast").api.map(_.signature) shouldContain ".info(message: String, durationMs: Int)"
  }

  test("a hash names a page or one of its examples; old story ids still work") {
    Pages.route("#/button").map(r => (r.page.id, r.example.map(_.id))) shouldBe
      Some(("button", None))
    Pages.route("#/button/disabled").flatMap(_.example).map(_.id) shouldBe Some("button/disabled")
    Pages.route("#/controls/buttons").flatMap(_.example).map(_.id) shouldBe Some("button/variants")
    Pages.route("#/no/such").isEmpty shouldBe true
    Pages.route("").isEmpty shouldBe true
  }

  test("a URL without a hash opens Get started, which #/start also names") {
    Pages.first.page.id shouldBe "start"
    Pages.route("#/start").map(_.page) shouldBe Some(StartStories.page)
    Pages.groups.head.pages.map(_.name) shouldBe Seq("Get started")
    Pages.all.exists(_.id == "start") shouldBe false
  }

  test("Get started shows the build files, the imports, and the first app next to its code") {
    val m = mount(
      Gallery(
        Rx.variable(Pages.first),
        Rx.variable(ColorScheme.Light),
        Rx.variable(ViewMode.Split),
        embed = false
      )
    )
    settle().map { _ =>
      m.get(".story-title").textContent shouldBe "Get started"
      m.queryAll(".start-step").map(_.id) shouldBe StartView.steps.map(_._1)
      val files = m.queryAll(".code-snippet .code-file").map(_.textContent)
      files shouldContain "project/plugins.sbt"
      files shouldContain "build.sbt"
      files shouldContain "web/index.html"
      val build = m.queryAll(".code-snippet").find(_.textContent.contains("uniUiCssFile")).get
      build.textContent shouldContain s"\"uni-ui\" % \"${BuildInfo.uniVersion}\""
      // Each file has its own copy button
      m.queryAll(".code-snippet [aria-label='Copy the code']").size shouldBe
        m.queryAll(".code-snippet").size
      // The first app runs in the example card, next to its code
      m.querySelector(".story-stage input").isDefined shouldBe true
      m.get(".story-card .code-block").textContent shouldContain "Hello().renderTo(\"app\")"
      m.get(".gallery-sidebar [aria-selected=true]").textContent shouldContain "Get started"
    }
  }

  test("a region keeps its file and lines, so the GitHub link points at the code") {
    val src = story("button/variants").source.get
    src.file shouldBe
      "examples/ui-gallery/src/main/scala/example/gallery/stories/ControlStories.scala"
    src.code.startsWith("def buttonVariants: RxElement =") shouldBe true
    src.code.contains("#region") shouldBe false
    src.githubUrl shouldContain s"#L${src.startLine}-L${src.endLine}"
  }

  test("shown code drops the inline modifier, so copied code is a plain method") {
    val stillInline = StorySources
      .all
      .collect {
        case (id, src) if src.code.linesIterator.exists(_.trim.startsWith("inline def")) =>
          id
      }
    stillInline.toSeq shouldBe Seq.empty
  }

  test("every example mounts") {
    Pages
      .stories
      .foreach { s =>
        val m = mount(s.render())
        (m.container.childElementCount > 0) shouldBe true
        m.unmount()
      }
  }

  test("the gallery shows a page with a card per example") {
    val current = Rx.variable(Pages.route("#/tabs").get)
    val m       = mount(
      Gallery(current, Rx.variable(ColorScheme.Light), Rx.variable(ViewMode.Split), embed = false)
    )
    settle().map { _ =>
      m.get(".story-title").textContent shouldBe "Tabs"
      m.queryAll(".example-section").size shouldBe page("tabs").examples.size
      m.queryAll(".example-title").map(_.textContent) shouldBe page("tabs").examples.map(_.title)
      current := Pages.route("#/select").get
      m.get(".story-title").textContent shouldBe "Select"
      m.get(".code-block").textContent shouldContain "Select(size"
    }
  }

  test("embed mode shows one example's card, without the shell") {
    val m = mount(
      Gallery(
        Rx.variable(Pages.route("#/button/busy").get),
        Rx.variable(ColorScheme.Light),
        Rx.variable(ViewMode.Split),
        embed = true
      )
    )
    m.querySelector(".gallery-rail") shouldBe None
    m.querySelector("[role=tree]") shouldBe None
    m.queryAll(".story-card").size shouldBe 1
    m.get(".story-toolbar__title").textContent shouldBe "Busy"
  }

  test("the view switch shows the preview, the code, both, or the agent view") {
    val mode = Rx.variable(ViewMode.Preview)
    val m    = mount(StoryView(Rx.variable(story("button/variants")), mode, embed = true))
    m.get(".story-body").getAttribute("data-view") shouldBe "preview"
    m.querySelector(".story-code-full") shouldBe None
    m.get(".uni-pane").getAttribute("data-state") shouldBe "closed"
    mode := ViewMode.Code
    m.querySelector(".story-code-full").isDefined shouldBe true
    mode := ViewMode.Split
    m.get(".uni-pane").getAttribute("data-state") shouldBe "open"
    m.querySelector(".story-side .code-view").isDefined shouldBe true
    mode := ViewMode.Agent
    m.querySelector(".story-side .agent-view").isDefined shouldBe true
  }

  test("on a narrow card the code goes below the preview instead of covering it") {
    val m = mount(
      div(
        styleAttr -> "width: 600px; height: 600px; display: flex; flex-direction: column",
        StoryView(Rx.variable(story("button/variants")), Rx.variable(ViewMode.Split), embed = true)
      )
    )
    settle()
      .flatMap(_ => settle())
      .map { _ =>
        m.get(".story-body").getAttribute("data-layout") shouldBe "stacked"
        m.querySelector(".story-side--stacked .code-view").isDefined shouldBe true
      }
  }

  test("the macro lists each component with the line that creates it") {
    val s     = story("button/variants")
    val src   = s.source.get
    val lines = src.code.split("\n").toSeq
    s.kinds shouldBe Seq("Button")
    s.tags.size shouldBe 6
    s.tags.foreach(t => lines(t.line - src.startLine) shouldContain "Button(")
    story("dialog/basic").kinds shouldBe Seq("Button", "Dialog", "Input")
    // MenuItem entries are MenuEntry, not RxElement, so the macro leaves them as they are
    story("menu/basic").kinds.toSet shouldBe Set("Menu", "Button")
  }

  test("tagged components carry their kind and line in the DOM") {
    val s = story("button/variants")
    val m = mount(s.render())
    settle().map { _ =>
      val tagged = m.queryAll("[data-src-kind=Button]")
      tagged.size shouldBe 6
      tagged.head.getAttribute("data-src-line") shouldBe s.tags.head.line.toString
    }
  }

  private def splitView(id: String): Mounted = mount(
    StoryView(Rx.variable(story(id)), Rx.variable(ViewMode.Split), embed = false)
  )

  test("a chip marks its component's lines") {
    val m = splitView("dialog/basic")
    settle().map { _ =>
      val chip = m.queryAll(".chip").find(_.textContent == "Dialog").get
      fireEvent.mouseEnter(chip)
      val dialogLine = story("dialog/basic").linesOf("Dialog").head.toString
      m.queryAll(".code-line.is-lit").map(_.getAttribute("data-line")) shouldBe Seq(dialogLine)
      fireEvent.mouseLeave(chip)
      m.queryAll(".code-line.is-lit").size shouldBe 0
    }
  }

  test("hovering a code line outlines what it creates, with its name and line") {
    val m = splitView("button/variants")
    settle().map { _ =>
      val line = story("button/variants").tags.head.line
      fireEvent.mouseEnter(m.get(s".code-line[data-line='${line}']"))
      val labels = m.queryAll(".inspect-label").map(_.textContent)
      labels.size shouldBe 1
      labels.head shouldContain s"Button · L${line}"
    }
  }

  test("with Inspect on, hovering the preview marks the line that made the part") {
    val m = splitView("button/variants")
    settle().map { _ =>
      fireEvent.click(m.get("[aria-label=Inspect]"))
      val button = m.get(".story-stage .uni-button")
      fireEvent.mouseMove(button)
      val line = button.closest("[data-src-line]").getAttribute("data-src-line")
      m.queryAll(".code-line.is-lit").map(_.getAttribute("data-line")) shouldBe Seq(line)
    }
  }

  test("a playground writes its props into the code and the preview") {
    val s = story("button/props")
    s.kinds shouldBe Seq("Button")
    val pg = s
      .playground
      .get
      .asInstanceOf[Playground[example.gallery.stories.ControlStories.ButtonProps]]
    var code = ""
    s.code.run(c => code = c)
    code shouldContain "Button(\"Save\")"
    pg.props
      .update(_.copy(label = "Send", variant = wvlet.uni.ui.ButtonVariant.Danger, disabled = true))
    code shouldContain "Button(\"Send\")"
    code shouldContain ".withVariant(ButtonVariant.Danger)"
    code shouldContain ".withDisabled(true)"
    val m = mount(s.render())
    m.get(".uni-button").textContent shouldBe "Send"
    pg.reset()
    m.get(".uni-button").textContent shouldBe "Save"
  }

  test("the agent view lists what an agent sees and runs its actions") {
    val m = mount(
      StoryView(Rx.variable(story("select/basic")), Rx.variable(ViewMode.Agent), embed = false)
    )
    settle()
      .flatMap(_ => settle())
      .map { _ =>
        m.queryAll(".agent-kind").map(_.textContent) shouldContain "select"
        fireEvent.click(m.queryAll(".agent-chip").find(_.textContent == "Large").get)
        m.get(".story-stage .demo-note").textContent shouldBe "Size: Large"
      }
  }

  test("the event log lists each change with who made it") {
    val m = mount(
      StoryView(Rx.variable(story("select/basic")), Rx.variable(ViewMode.Agent), embed = false)
    )
    settle()
      .flatMap(_ => settle())
      .map { _ =>
        fireEvent.click(m.queryAll(".agent-chip").find(_.textContent == "Small").get)
        val row = m.get(".event-row")
        row.querySelector(".event-value").textContent shouldBe "Medium → Small"
        row.querySelector(".event-source").textContent shouldBe "agent"
      }
  }

  test("the parts table lists the data-slot parts the examples render") {
    val m = mount(PageView(page("tabs"), None, ViewMode.Preview))
    waitMs(400).map { _ =>
      val slots = m
        .queryAll("#parts .ref-table tbody tr")
        .map(_.querySelectorAll("code")(1).textContent)
      slots shouldContain "tab-panel"
      slots shouldContain "tab"
    }
  }

  test("the accessibility check runs checkPattern on the live components") {
    val m = mount(PageView(page("tabs"), None, ViewMode.Preview))
    waitMs(700).map { _ =>
      val rows = m.queryAll(".a11y-row")
      (rows.size > 0) shouldBe true
      rows.forall(_.classList.contains("a11y-row--pass")) shouldBe true
    }
  }

  /**
    * Polls `cond` every 50 ms, up to `ms`, so a test can wait for the Colors page's probe frames.
    */
  private def waitFor(ms: Int)(cond: => Boolean): Rx[Unit] =
    if cond || ms <= 0 then
      Rx.const(())
    else
      waitMs(50).flatMap(_ => waitFor(ms - 50)(cond))

  /** Loads uni-ui.css into the test page, as index.html does in the gallery. */
  private def withUniUiCss[A](body: => Rx[A]): Rx[A] =
    val style = dom.document.createElement("style")
    style.textContent = UiCssTokens.text
    dom.document.head.appendChild(style)
    body.tapOn { case _ =>
      style.remove()
    }

  test("the Colors page lists every color variable uni-ui.css declares, once") {
    val names = ColorsPage.tokens.map(_.name)
    names.distinct shouldBe names
    names.toSet shouldBe UiCssTokens.declared.filter(ColorsPage.isColor).toSet
  }

  test("#/colors opens the Colors guide page in the Foundations group") {
    val r = Pages.route("#/colors").get
    r.page.id shouldBe "colors"
    r.example shouldBe None
    r.story shouldBe None
    Pages.groupOf(r.page).map(_.id) shouldBe Some("foundations")
    Pages.all.exists(_.id == "colors") shouldBe false
  }

  test("each swatch shows the light and dark values the cascade resolves") {
    withUniUiCss {
      val m                                           = mount(ColorsView())
      def value(token: String, theme: String): String = m
        .get(s"[data-token='${token}'] [data-theme-value='${theme}']")
        .getAttribute("data-copy")
      waitFor(3000)(
        m.querySelector("[data-token='canvas'] [data-theme-value='dark']")
          .exists(_.getAttribute("data-copy").nonEmpty)
      ).map { _ =>
        m.queryAll(".swatch").size shouldBe ColorsPage.tokens.size
        value("canvas", "light") shouldBe "#eef3f4"
        value("canvas", "dark") shouldBe "#061114"
        value("accent", "light") shouldBe "#2e7685"
        value("accent", "dark") shouldBe "#25a7c1"
        // --uni-glow has one value for both themes
        value("glow", "dark") shouldBe "#08bdcb"
        m.get("[data-token='overlay-shadow'] .swatch-shadow").getAttribute("style") shouldContain
          "box-shadow"
      }
    }
  }

  test("clicking a swatch copies its var() and says whether that worked") {
    val m    = mount(ColorsView())
    val chip = m.get("[data-token='accent'] .swatch-chip")
    chip.getAttribute("data-copy") shouldBe "var(--uni-accent)"
    fireEvent.click(chip)
    // Headless Chromium may refuse a synthetic click's clipboard write; either way the chip says so
    waitFor(2000)(chip.querySelector(".swatch-status").textContent.nonEmpty).map { _ =>
      Seq("Copied", "Couldn't copy") shouldContain chip.querySelector(".swatch-status").textContent
    }
  }

  test("the gallery shows the Colors page in the sidebar and as an embed") {
    val m = mount(
      Gallery(
        Rx.variable(Pages.route("#/colors").get),
        Rx.variable(ColorScheme.Light),
        Rx.variable(ViewMode.Split),
        embed = false
      )
    )
    settle().map { _ =>
      m.get(".story-title").textContent shouldBe "Colors"
      m.queryAll(".colors-group").size shouldBe ColorsPage.groups.size
      m.queryAll(".nav-story").map(_.textContent) shouldContain "Colors"
      val e = mount(
        Gallery(
          Rx.variable(Pages.route("#/colors").get),
          Rx.variable(ColorScheme.Light),
          Rx.variable(ViewMode.Split),
          embed = true
        )
      )
      e.queryAll(".swatch").size shouldBe ColorsPage.tokens.size
      e.querySelector(".gallery-rail") shouldBe None
    }
  }

  test("an embed follows the hash between an example and the Colors page") {
    val current = Rx.variable(Pages.route("#/button/busy").get)
    val m       = mount(
      Gallery(current, Rx.variable(ColorScheme.Light), Rx.variable(ViewMode.Split), embed = true)
    )
    settle().map { _ =>
      m.get(".story-toolbar__title").textContent shouldBe "Busy"
      current := Pages.route("#/colors").get
      m.queryAll(".swatch").size shouldBe ColorsPage.tokens.size
      current := Pages.route("#/button/disabled").get
      m.queryAll(".swatch").size shouldBe 0
      m.get(".story-toolbar__title").textContent shouldBe "Disabled"
    }
  }

  test("highlighting marks keywords, types, strings, and comments") {
    import ScalaHighlight.Kind
    val kinds = ScalaHighlight
      .tokens("""val n = Button("Save") // done""")
      .filter(_.kind != Kind.Plain)
      .map(t => (t.text, t.kind))
    kinds shouldBe
      Seq(
        ("val", Kind.Keyword),
        ("Button", Kind.TypeName),
        ("\"Save\"", Kind.Str),
        ("// done", Kind.Comment)
      )
  }

end GalleryTest
