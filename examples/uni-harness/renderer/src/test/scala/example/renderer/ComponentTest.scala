package example.renderer

import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.collection.mutable

/** The composer, the panes, and the rail, rendered into a real DOM with no server. */
class ComponentTest extends DomTestSupport:

  test("the composer sends trimmed text on Enter and clears itself"):
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "  hello  ")
    fireEvent.keyDown(box, "Enter")
    sent.toSeq shouldBe Seq("hello")
    box.asInstanceOf[dom.html.TextArea].value shouldBe ""

  test("the composer keeps Shift+Enter and IME Enter as text input"):
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "こんにちは")
    fireEvent.keyDown(box, "Enter", shiftKey = true)
    fireEvent.keyDown(box, "Enter", isComposing = true)
    sent.toSeq shouldBe Seq.empty

  test("the composer waits while a reply is on its way"):
    val sent = mutable.Buffer.empty[String]
    val busy = Rx.variable(true)
    val m    = mount(Composer(sent += _, busy))
    // onMount wiring runs a step after mount
    settle().map { _ =>
      val box = m.get("textarea")
      fireEvent.input(box, "later")
      fireEvent.keyDown(box, "Enter")
      sent.toSeq shouldBe Seq.empty
      m.get("[aria-label=Send]").hasAttribute("disabled") shouldBe true
      busy := false
      fireEvent.keyDown(box, "Enter")
      sent.toSeq shouldBe Seq("later")
    }

  private def paneWidth(m: Mounted): String =
    m.get("aside").asInstanceOf[dom.html.Element].style.width

  test("a foldable pane collapses to zero width and back"):
    val open = Rx.variable(true)
    val m    = mount(FoldablePane(open, Rx.variable(300), Edge.Left)())
    paneWidth(m) shouldBe "300px"
    open := false
    paneWidth(m) shouldBe "0px"

  test("dragging the resize handle changes the width within its range"):
    val width  = Rx.variable(272)
    val handle = ResizeHandle(width, Layout.sidebarWidth, Edge.Right, "Resize")
    val m      = mount(FoldablePane(Rx.variable(true), width, Edge.Right, Some(handle))())
    val grip   = m.get("[role=separator]")
    fireEvent.pointerDown(grip, clientX = 300)
    fireEvent.pointerMove(dom.window, clientX = 350)
    width.get shouldBe 322
    fireEvent.pointerMove(dom.window, clientX = 1000)
    width.get shouldBe Layout.sidebarWidth.max
    fireEvent.pointerUp(dom.window, clientX = 1000)
    // After pointerup, moves no longer resize
    fireEvent.pointerMove(dom.window, clientX = 0)
    width.get shouldBe Layout.sidebarWidth.max
    fireEvent.dblclick(grip)
    width.get shouldBe Layout.sidebarWidth.default

  test("a handle on the left edge grows the pane as it moves left"):
    val width  = Rx.variable(300)
    val handle = ResizeHandle(width, SizeRange(200, 300, 400), Edge.Left, "Resize")
    val m      = mount(handle)
    fireEvent.pointerDown(m.get("[role=separator]"), clientX = 500)
    fireEvent.pointerMove(dom.window, clientX = 450)
    fireEvent.pointerUp(dom.window, clientX = 450)
    width.get shouldBe 350

  test("the dock's left-edge handle resizes the shared dock width and resets it"):
    val layout = Layout.inMemory()
    val handle = ResizeHandle(layout.dockWidth, Layout.dockWidth, Edge.Left, "Resize dock")
    val m      = mount(FoldablePane(layout.dockOpen, layout.dockWidth, Edge.Left, Some(handle))())
    val grip   = m.get("[role=separator]")
    fireEvent.pointerDown(grip, clientX = 900)
    fireEvent.pointerMove(dom.window, clientX = 0)
    fireEvent.pointerUp(dom.window, clientX = 0)
    layout.dockWidth.get shouldBe Layout.dockWidth.max
    paneWidth(m) shouldBe s"${Layout.dockWidth.max}px"
    fireEvent.dblclick(grip)
    layout.dockWidth.get shouldBe Layout.dockWidth.default

  test("a dock width stored under an older, larger max loads clamped and is saved back"):
    val prefix = "component-test.clamp."
    val key    = s"${prefix}dockWidth"
    dom.window.localStorage.setItem(key, "960")
    try
      val layout = Layout.persistent(prefix)
      ResizeHandle(layout.dockWidth, Layout.dockWidth, Edge.Left, "Resize dock")
      layout.dockWidth.get shouldBe Layout.dockWidth.max
      dom.window.localStorage.getItem(key) shouldBe s"${Layout.dockWidth.max}"
    finally
      dom.window.localStorage.removeItem(key)

  test("clicking the active rail icon folds the sidebar; another icon switches views"):
    val layout = Layout.inMemory()
    val m      = mount(IconRail(layout))
    fireEvent.click(m.get("[aria-label=Files]"))
    layout.view.get shouldBe View.Files
    layout.sidebarOpen.get shouldBe true
    fireEvent.click(m.get("[aria-label=Files]"))
    layout.sidebarOpen.get shouldBe false

  test("the theme button cycles System, Light, Dark and sets data-theme"):
    val layout = Layout.inMemory()
    val root   = dom.document.createElement("div")
    val sub    = layout.theme.run(t => Theme.applyTo(root, t))
    val m      = mount(IconRail(layout))
    fireEvent.click(m.get("[aria-label=Theme]"))
    layout.theme.get shouldBe Theme.Light
    root.getAttribute("data-theme") shouldBe "light"
    fireEvent.click(m.get("[aria-label=Theme]"))
    root.getAttribute("data-theme") shouldBe "dark"
    fireEvent.click(m.get("[aria-label=Theme]"))
    root.hasAttribute("data-theme") shouldBe false
    sub.cancel

end ComponentTest
