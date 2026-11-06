/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.SizeRange
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*
import wvlet.uni.rx.Rx

import scala.language.implicitConversions

class ComponentTest extends DomTestSupport:

  test("a button runs its action and carries its variant and size classes") {
    var clicks = 0
    val m      = mount(Button("Send").primary.small.onClick(() => clicks += 1))
    val b      = m.get("button")
    b.getAttribute("type") shouldBe "button"
    b.getAttribute("class") shouldBe "uni-button uni-button--primary uni-button--sm"
    fireEvent.click(b)
    clicks shouldBe 1
  }

  test("a disabled button follows its Rx and ignores clicks") {
    var clicks = 0
    val off    = Rx.variable(true)
    val m      = mount(Button("Go").withDisabled(off).onClick(() => clicks += 1))
    val b      = m.get("button").asInstanceOf[dom.html.Button]
    b.disabled shouldBe true
    fireEvent.click(b)
    clicks shouldBe 0
    off := false
    b.disabled shouldBe false
    fireEvent.click(b)
    clicks shouldBe 1
  }

  test("an icon button is a ghost square with an accessible name") {
    val m = mount(IconButton("Close", "×"))
    val b = m.get("button")
    b.getAttribute("aria-label") shouldBe "Close"
    b.classList.contains("uni-button--ghost") shouldBe true
    b.classList.contains("uni-button--icon") shouldBe true
  }

  test("extra modifiers keep the component's classes") {
    val m = mount(Button("Save")(wvlet.uni.dom.all.cls += "wide"))
    m.get("button").getAttribute("class") shouldBe
      "uni-button uni-button--default uni-button--md wide"
  }

  test("an input binds its text both ways and shows aria-invalid") {
    val text    = Rx.variable("a")
    val invalid = Rx.variable(false)
    val m  = mount(Input(text).withPlaceholder("Search").withLabel("Search").withInvalid(invalid))
    val el = m.get("input").asInstanceOf[dom.html.Input]
    el.value shouldBe "a"
    el.getAttribute("placeholder") shouldBe "Search"
    el.getAttribute("aria-label") shouldBe "Search"
    fireEvent.input(el, "abc")
    text.get shouldBe "abc"
    text := "xyz"
    el.value shouldBe "xyz"
    el.getAttribute("aria-invalid") shouldBe "false"
    invalid := true
    el.getAttribute("aria-invalid") shouldBe "true"
  }

  test("a textarea binds its text and sets rows") {
    val text = Rx.variable("")
    val m    = mount(Textarea(text).withRows(5))
    val el   = m.get("textarea")
    el.getAttribute("rows") shouldBe "5"
    fireEvent.input(el, "line 1\nline 2")
    text.get shouldBe "line 1\nline 2"
  }

  private enum Pick:
    case A,
      B,
      C

  private def tabs(m: Mounted): Seq[dom.Element] = m.queryAll("[role=tab]")

  test("tabs select on click and mark one tab stop") {
    val sel = Rx.variable(Pick.A)
    val m   = mount(TabList(sel, "Picks")(Tab(Pick.A, "A"), Tab(Pick.B, "B"), Tab(Pick.C, "C")))
    m.get("[role=tablist]").getAttribute("aria-label") shouldBe "Picks"
    tabs(m).map(_.getAttribute("aria-selected")) shouldBe Seq("true", "false", "false")
    tabs(m).map(_.getAttribute("tabindex")) shouldBe Seq("0", "-1", "-1")
    fireEvent.click(tabs(m)(2))
    sel.get shouldBe Pick.C
    tabs(m).map(_.getAttribute("aria-selected")) shouldBe Seq("false", "false", "true")
  }

  test("arrow keys, Home, and End move the selection and the focus") {
    val sel = Rx.variable(Pick.A)
    val m   = mount(TabList(sel, "Picks")(Tab(Pick.A, "A"), Tab(Pick.B, "B"), Tab(Pick.C, "C")))
    fireEvent.keyDown(tabs(m)(0), "ArrowRight")
    sel.get shouldBe Pick.B
    dom.document.activeElement shouldBe tabs(m)(1)
    fireEvent.keyDown(tabs(m)(1), "End")
    sel.get shouldBe Pick.C
    fireEvent.keyDown(tabs(m)(2), "ArrowRight")
    sel.get shouldBe Pick.A
    fireEvent.keyDown(tabs(m)(0), "ArrowLeft")
    sel.get shouldBe Pick.C
    fireEvent.keyDown(tabs(m)(2), "Home")
    sel.get shouldBe Pick.A
  }

  test("a tab panel renders the selected tab's content") {
    val sel  = Rx.variable(Pick.A)
    val tabs = TabList(sel, "Picks")(Tab(Pick.A, "A"), Tab(Pick.B, "B"))
    val m    = mount(
      wvlet.uni.dom.all.div(tabs, TabPanel(tabs)(p => wvlet.uni.dom.all.div(s"panel ${p}")))
    )
    val panel = m.get("[role=tabpanel]")
    panel.textContent shouldBe "panel A"
    // The tabs and the panel point at each other
    val ts = m.queryAll("[role=tab]")
    ts.map(_.getAttribute("aria-controls")).distinct shouldBe Seq(panel.id)
    panel.getAttribute("aria-labelledby") shouldBe ts(0).id
    sel := Pick.B
    panel.textContent shouldBe "panel B"
    panel.getAttribute("aria-labelledby") shouldBe ts(1).id
  }

  test("two tab lists never share ids") {
    val a = TabList(Rx.variable(Pick.A), "One")(Tab(Pick.A, "A"))
    val b = TabList(Rx.variable(Pick.A), "Two")(Tab(Pick.A, "A"))
    (a.panelId == b.panelId) shouldBe false
  }

  test("an app's own handlers run alongside the components' handlers") {
    import wvlet.uni.dom.all.{onclick, onkeydown}
    var clicks    = 0
    var appClicks = 0
    val m1        = mount(
      Button("Go").onClick(() => clicks += 1)(
        onclick -> { () =>
          appClicks += 1
        }
      )
    )
    fireEvent.click(m1.get("button"))
    (clicks, appClicks) shouldBe (1, 1)
    val sel  = Rx.variable(Pick.A)
    var keys = 0
    val m2   = mount(
      TabList(sel, "Picks")(Tab(Pick.A, "A"), Tab(Pick.B, "B"))(
        onkeydown -> { () =>
          keys += 1
        }
      )
    )
    fireEvent.keyDown(m2.queryAll("[role=tab]")(0), "ArrowRight")
    sel.get shouldBe Pick.B
    keys shouldBe 1
  }

  private def paneWidth(m: Mounted): String =
    m.get("aside").asInstanceOf[dom.html.Element].style.width

  test("a foldable pane collapses to zero width and back") {
    val open = Rx.variable(true)
    val m    = mount(FoldablePane(open, Rx.variable(300), Edge.Left)())
    paneWidth(m) shouldBe "300px"
    m.get("aside").getAttribute("data-state") shouldBe "open"
    open := false
    paneWidth(m) shouldBe "0px"
    m.get("aside").getAttribute("data-state") shouldBe "closed"
  }

  private val range = SizeRange(min = 200, default = 300, max = 400)

  test("dragging a splitter resizes within its range, and double-click resets") {
    val width = Rx.variable(300)
    val m     = mount(
      FoldablePane(
        Rx.variable(true),
        width,
        Edge.Right,
        Some(Splitter(width, range, Edge.Right, "Resize"))
      )()
    )
    val grip = m.get("[role=separator]")
    grip.getAttribute("aria-label") shouldBe "Resize"
    fireEvent.pointerDown(grip, clientX = 300)
    fireEvent.pointerMove(dom.window, clientX = 350)
    width.get shouldBe 350
    m.get("aside").getAttribute("data-dragging") shouldBe "true"
    fireEvent.pointerMove(dom.window, clientX = 1000)
    width.get shouldBe 400
    fireEvent.pointerUp(dom.window, clientX = 1000)
    m.get("aside").getAttribute("data-dragging") shouldBe "false"
    fireEvent.dblclick(grip)
    width.get shouldBe 300
  }

  test("a splitter resizes with the keyboard and reports its value") {
    val width = Rx.variable(300)
    val m     = mount(Splitter(width, range, Edge.Left, "Resize dock"))
    val grip  = m.get("[role=separator]")
    grip.getAttribute("tabindex") shouldBe "0"
    // A left-edge grip grows its pane as it moves left
    fireEvent.keyDown(grip, "ArrowLeft")
    width.get shouldBe 300 + Splitter.step
    grip.getAttribute("aria-valuenow") shouldBe s"${300 + Splitter.step}"
    fireEvent.keyDown(grip, "ArrowRight", shiftKey = true)
    width.get shouldBe 300 + Splitter.step - Splitter.largeStep
    fireEvent.keyDown(grip, "End")
    width.get shouldBe 400
    fireEvent.keyDown(grip, "Home")
    width.get shouldBe 200
    fireEvent.keyDown(grip, "Enter")
    width.get shouldBe 300
  }

  test("a badge shows its tone and a dot carries a label") {
    val m = mount(
      wvlet.uni.dom.all.div(Badge("3"), Badge.dot("New output").withTone(BadgeTone.Danger))
    )
    val badges = m.queryAll(".uni-badge")
    badges(0).textContent shouldBe "3"
    badges(0).classList.contains("uni-badge--neutral") shouldBe true
    badges(1).getAttribute("aria-label") shouldBe "New output"
    badges(1).classList.contains("uni-badge--danger") shouldBe true
  }

  test("kbd and status bar render their elements") {
    val m = mount(StatusBar(Kbd("⌘"), StatusBar.spacer, "UTF-8"))
    m.get("footer.uni-statusbar kbd.uni-kbd").textContent shouldBe "⌘"
    m.get("footer").textContent shouldBe "⌘UTF-8"
  }

  test("ColorScheme sets data-theme, and System removes it") {
    val root   = dom.document.createElement("div")
    val scheme = Rx.variable(ColorScheme.Dark)
    val c      = ColorScheme.bind(scheme, root)
    root.getAttribute("data-theme") shouldBe "dark"
    scheme := ColorScheme.Light
    root.getAttribute("data-theme") shouldBe "light"
    scheme := ColorScheme.System
    root.hasAttribute("data-theme") shouldBe false
    c.cancel
  }

end ComponentTest
