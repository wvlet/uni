package example.gallery

import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx
import wvlet.uni.ui.ColorScheme

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
    src.code.startsWith("def buttons: RxElement =") shouldBe true
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

  test("the view switch shows the preview, the code, or both") {
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
  }

  test("chips name the components a story uses, in order") {
    val code = Stories.find("overlays/dialog").flatMap(_.source).get.code
    Spotlight.usedIn(code) shouldBe Seq("Button", "Dialog", "Input", "AlertDialog")
    Spotlight.creates("""    Button("Save").primary""", "Button") shouldBe true
    Spotlight.creates("""    IconButton("Close", x)""", "Button") shouldBe false
  }

  test("a chip outlines its component in the preview and marks its code lines") {
    val m = mount(
      StoryView(
        Rx.variable(Stories.find("controls/buttons").get),
        Rx.variable(ViewMode.Split),
        embed = false
      )
    )
    settle().map { _ =>
      val chip = m.get(".chip")
      fireEvent.mouseEnter(chip)
      (m.queryAll(".story-stage .uni-button.is-spotlit").size > 0) shouldBe true
      (m.queryAll(".code-line.is-spotlit").size > 0) shouldBe true
      fireEvent.mouseLeave(chip)
      m.queryAll(".is-spotlit").size shouldBe 0
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
