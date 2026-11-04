package example.renderer

import org.scalajs.dom
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The composer, the panes, and the rail, rendered into a real DOM with no server. */
class ComponentTest extends DomTestSupport:
  import scala.concurrent.ExecutionContext.Implicits.global

  private def keyDown(el: dom.Element, shift: Boolean = false, composing: Boolean = false): Unit =
    val init = js
      .Dynamic
      .literal(
        key = "Enter",
        shiftKey = shift,
        isComposing = composing,
        bubbles = true,
        cancelable = true
      )
      .asInstanceOf[dom.KeyboardEventInit]
    el.dispatchEvent(new dom.KeyboardEvent("keydown", init))

  test("the composer sends trimmed text on Enter and clears itself"):
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "  hello  ")
    keyDown(box)
    sent.toSeq shouldBe Seq("hello")
    box.asInstanceOf[dom.html.TextArea].value shouldBe ""

  test("the composer keeps Shift+Enter and IME Enter as text input"):
    val sent = mutable.Buffer.empty[String]
    val m    = mount(Composer(sent += _, Rx.variable(false)))
    val box  = m.get("textarea")
    fireEvent.input(box, "こんにちは")
    keyDown(box, shift = true)
    keyDown(box, composing = true)
    sent.toSeq shouldBe Seq.empty

  /** onMount runs once the browser reports the element attached, a tick after mount. */
  private def afterMount(): Future[Unit] =
    val p = Promise[Unit]()
    dom.window.setTimeout(() => p.success(()), 0)
    p.future

  test("the composer waits while a reply is on its way"):
    val sent = mutable.Buffer.empty[String]
    val busy = Rx.variable(true)
    val m    = mount(Composer(sent += _, busy))
    afterMount().map { _ =>
      val box = m.get("textarea")
      fireEvent.input(box, "later")
      keyDown(box)
      sent.toSeq shouldBe Seq.empty
      m.get("[aria-label=Send]").hasAttribute("disabled") shouldBe true
      busy := false
      keyDown(box)
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

  private def pointer(target: dom.EventTarget, kind: String, x: Double): Unit =
    val init = js
      .Dynamic
      .literal(clientX = x, bubbles = true, cancelable = true)
      .asInstanceOf[dom.PointerEventInit]
    target.dispatchEvent(new dom.PointerEvent(kind, init))

  test("dragging the resize handle changes the width within its range"):
    val width  = Rx.variable(272)
    val handle = ResizeHandle(width, Layout.sidebarWidth, Edge.Right, "Resize")
    val m      = mount(FoldablePane(Rx.variable(true), width, Edge.Right, Some(handle))())
    val grip   = m.get("[role=separator]")
    pointer(grip, "pointerdown", 300)
    pointer(dom.window, "pointermove", 350)
    width.get shouldBe 322
    pointer(dom.window, "pointermove", 1000)
    width.get shouldBe Layout.sidebarWidth.max
    pointer(dom.window, "pointerup", 1000)
    // After pointerup, moves no longer resize
    pointer(dom.window, "pointermove", 0)
    width.get shouldBe Layout.sidebarWidth.max
    fireEvent.dblclick(grip)
    width.get shouldBe Layout.sidebarWidth.default

  test("a handle on the left edge grows the pane as it moves left"):
    val width  = Rx.variable(300)
    val handle = ResizeHandle(width, SizeRange(200, 300, 400), Edge.Left, "Resize")
    val m      = mount(handle)
    pointer(m.get("[role=separator]"), "pointerdown", 500)
    pointer(dom.window, "pointermove", 450)
    pointer(dom.window, "pointerup", 450)
    width.get shouldBe 350

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
