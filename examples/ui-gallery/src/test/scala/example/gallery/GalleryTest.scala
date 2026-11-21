package example.gallery

import wvlet.uni.dom.all.{div, styleAttr}
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.ColorScheme

import scala.language.implicitConversions

/** The stories, their code, and the gallery shell, rendered into a real DOM. */
class GalleryTest extends DomTestSupport:

  test("every story has a // #region block for its code panel") {
    Stories.all.filter(_.source.isEmpty).map(_.id) shouldBe Seq.empty
  }

  test("story ids are unique and start with their group") {
    Stories.all.map(_.id).distinct.size shouldBe Stories.all.size
    Stories.groups.foreach(g => g.stories.foreach(s => s.group shouldBe g.id))
  }

  test("a region keeps its file and lines, so the GitHub link points at the code") {
    val src = Stories.find("controls/buttons").flatMap(_.source).get
    src.file shouldBe
      "examples/ui-gallery/src/main/scala/example/gallery/stories/ControlStories.scala"
    src.code.startsWith("inline def buttons: RxElement =") shouldBe true
    src.code.contains("#region") shouldBe false
    (src.endLine > src.startLine) shouldBe true
    src.githubUrl shouldContain s"#L${src.startLine}-L${src.endLine}"
  }

  test("every story mounts") {
    Stories
      .all
      .foreach { s =>
        val m = mount(s.render())
        (m.container.childElementCount > 0) shouldBe true
        m.unmount()
      }
  }

  test("the hash names the story") {
    Gallery.storyOf("#/overlays/menu").map(_.id) shouldBe Some("overlays/menu")
    Gallery.storyOf("#/no/such").isEmpty shouldBe true
    Gallery.storyOf("").isEmpty shouldBe true
  }

  test("the gallery shows the current story and its code") {
    val current = Rx.variable(Stories.find("controls/tabs").get)
    val m       = mount(
      Gallery(current, Rx.variable(ColorScheme.Light), Rx.variable(ViewMode.Split), embed = false)
    )
    settle().map { _ =>
      m.get(".story-title").textContent shouldBe "Tabs"
      m.get(".code-line").getAttribute("data-line") shouldBe
        current.get.source.get.startLine.toString
      current := Stories.find("overlays/select").get
      m.get(".story-title").textContent shouldBe "Select"
      m.get(".code-block").textContent shouldContain "Select(selected"
    }
  }

  test("embed mode leaves out the header and the sidebar") {
    val m = mount(
      Gallery(
        Rx.variable(Stories.all.head),
        Rx.variable(ColorScheme.Light),
        Rx.variable(ViewMode.Split),
        embed = true
      )
    )
    m.querySelector(".gallery-rail") shouldBe None
    m.querySelector("[role=tree]") shouldBe None
    m.querySelector(".story-hero") shouldBe None
    m.querySelector(".code-view").isDefined shouldBe true
  }

  test("the view switch shows the preview, the code, both, or the agent view") {
    val mode = Rx.variable(ViewMode.Preview)
    val m = mount(StoryView(Rx.variable(Stories.find("controls/buttons").get), mode, embed = true))
    m.get(".story-body").getAttribute("data-view") shouldBe "preview"
    m.querySelector(".story-code-full") shouldBe None
    m.get(".uni-pane").getAttribute("data-state") shouldBe "closed"
    mode := ViewMode.Code
    m.querySelector(".story-code-full").isDefined shouldBe true
    mode := ViewMode.Split
    m.querySelector(".story-code-full") shouldBe None
    m.get(".uni-pane").getAttribute("data-state") shouldBe "open"
    m.querySelector(".story-side .code-view").isDefined shouldBe true
    mode := ViewMode.Agent
    m.querySelector(".story-side .agent-view").isDefined shouldBe true
  }

  test("on a narrow card the code goes below the preview instead of covering it") {
    val m = mount(
      div(
        styleAttr -> "width: 600px; height: 600px; display: flex; flex-direction: column",
        StoryView(
          Rx.variable(Stories.find("controls/buttons").get),
          Rx.variable(ViewMode.Split),
          embed = true
        )
      )
    )
    settle()
      .flatMap(_ => settle())
      .map { _ =>
        m.get(".story-body").getAttribute("data-layout") shouldBe "stacked"
        m.querySelector(".story-side--stacked .code-view").isDefined shouldBe true
        m.get(".uni-pane").getAttribute("data-state") shouldBe "closed"
      }
  }

  test("the macro lists each component with the line that creates it") {
    val story = Stories.find("controls/buttons").get
    val src   = story.source.get
    val lines = src.code.split("\n").toSeq
    story.kinds shouldBe Seq("Button")
    story.tags.size shouldBe 7
    story.tags.foreach(t => lines(t.line - src.startLine) shouldContain "Button(")
    Stories.find("overlays/dialog").get.kinds shouldBe
      Seq("Button", "Dialog", "Input", "AlertDialog")
  }

  test("tagged components carry their kind and line in the DOM") {
    val story = Stories.find("controls/buttons").get
    val m     = mount(story.render())
    settle().map { _ =>
      val tagged = m.queryAll("[data-src-kind=Button]")
      tagged.size shouldBe 7
      tagged.head.getAttribute("data-src-line") shouldBe story.tags.head.line.toString
    }
  }

  test("a component passed where its own type is needed keeps that type, untagged") {
    // MenuItem entries are MenuEntry, not RxElement, so the macro leaves them as they are
    Stories.find("overlays/menu").get.kinds.toSet shouldBe Set("Menu", "Button")
  }

  private def splitView(id: String): Mounted = mount(
    StoryView(Rx.variable(Stories.find(id).get), Rx.variable(ViewMode.Split), embed = false)
  )

  test("a chip marks its component's lines and outlines it in the preview") {
    val m = splitView("overlays/dialog")
    settle().map { _ =>
      val chip = m.queryAll(".chip").find(_.textContent == "Dialog").get
      fireEvent.mouseEnter(chip)
      val dialogLine = Stories.find("overlays/dialog").get.linesOf("Dialog").head.toString
      m.queryAll(".code-line.is-lit").map(_.getAttribute("data-line")) shouldBe Seq(dialogLine)
      fireEvent.mouseLeave(chip)
      m.queryAll(".code-line.is-lit").size shouldBe 0
    }
  }

  test("hovering a code line outlines what it creates, with its name and line") {
    val m = splitView("controls/buttons")
    settle().map { _ =>
      val line = Stories.find("controls/buttons").get.tags.head.line
      fireEvent.mouseEnter(m.get(s".code-line[data-line='${line}']"))
      val labels = m.queryAll(".inspect-label").map(_.textContent)
      labels.size shouldBe 1
      labels.head shouldContain s"Button · L${line}"
    }
  }

  test("with Inspect on, hovering the preview marks the line that made the part") {
    val m = splitView("controls/buttons")
    settle().map { _ =>
      fireEvent.click(m.get("[aria-label=Inspect]"))
      val button = m.get(".story-stage .uni-button")
      fireEvent.mouseMove(button)
      val line = button.closest("[data-src-line]").getAttribute("data-src-line")
      m.queryAll(".code-line.is-lit").map(_.getAttribute("data-line")) shouldBe Seq(line)
    }
  }

  test("a playground writes its props into the code and the preview") {
    val story = Stories.find("controls/button-props").get
    story.kinds shouldBe Seq("Button")
    val pg = story
      .playground
      .get
      .asInstanceOf[Playground[example.gallery.stories.ControlStories.ButtonProps]]
    var code = ""
    story.code.run(c => code = c)
    code shouldContain "Button(\"Save\")"
    code shouldContain ".withVariant(ButtonVariant.Primary)"
    pg.props
      .update(_.copy(label = "Send", variant = wvlet.uni.ui.ButtonVariant.Danger, disabled = true))
    code shouldContain "Button(\"Send\")"
    code shouldContain ".withVariant(ButtonVariant.Danger)"
    code shouldContain ".withDisabled(true)"
    val m = mount(story.render())
    m.get(".uni-button").textContent shouldBe "Send"
    m.get(".uni-button").classList.contains("uni-button--danger") shouldBe true
    pg.reset()
    m.get(".uni-button").textContent shouldBe "Save"
  }

  test("the agent view lists what an agent sees and runs its actions") {
    val m = mount(
      StoryView(
        Rx.variable(Stories.find("overlays/select").get),
        Rx.variable(ViewMode.Agent),
        embed = false
      )
    )
    settle()
      .flatMap(_ => settle())
      .map { _ =>
        m.queryAll(".agent-kind").map(_.textContent) shouldContain "select"
        val large = m.queryAll(".agent-chip").find(_.textContent == "Large").get
        fireEvent.click(large)
        m.get(".story-stage .demo-note").textContent shouldBe "Size: Large"
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
