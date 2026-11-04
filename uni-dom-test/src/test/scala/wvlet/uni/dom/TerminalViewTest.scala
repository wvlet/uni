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
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.cli.term.{TerminalConfig, TerminalEmulator}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*

import scala.collection.mutable
import scala.scalajs.js
import scala.language.implicitConversions

class TerminalViewTest extends DomTestSupport:

  private def setup(): (TerminalEmulator, TerminalView, Mounted, mutable.ArrayBuffer[String]) =
    val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 3))
    val sent = mutable.ArrayBuffer.empty[String]
    term.onData(sent += _)
    val view = TerminalView(term)
    val m    = mount(div(styleAttr -> "width: 400px; height: 200px;", view))
    (term, view, m, sent)

  private def rowTexts(m: Mounted): Seq[String] =
    val rows = m.container.querySelectorAll(".uni-term-row")
    (0 until rows.length).map(i => rows(i).textContent.replaceAll("\\s+$", ""))

  test("draw rows and styled runs") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      term.write("plain \u001b[31mred\u001b[0m\r\nnext")
      view.renderNow()
      val texts = rowTexts(m)
      texts.take(2) shouldBe Seq("plain red", "next")
      val red = m.container.querySelectorAll(".uni-term-row span")
      (0 until red.length).exists(i => red(i).textContent == "red") shouldBe true
    }
  }

  test("fit the terminal to the pane and report the size") {
    val term  = TerminalEmulator(TerminalConfig(cols = 20, rows = 3))
    val view  = TerminalView(term)
    var sizes = Seq.empty[(Int, Int)]
    view.onResize((c, r) => sizes :+= (c, r))
    mount(div(styleAttr -> "width: 400px; height: 200px;", view))
    settle().map { _ =>
      (term.cols > 20) shouldBe true
      (term.rows > 3) shouldBe true
      sizes.lastOption shouldBe Some((term.cols, term.rows))
    }
  }

  test("keep the scrollback while the alternate screen shows") {
    val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 2))
    val view = TerminalView(term)
    val m    = mount(div(styleAttr -> "width: 400px; height: 44px;", view))
    settle().map { _ =>
      term.write("a\r\nb\r\nc")
      view.renderNow()
      term.write("\u001b[?1049hvim")
      view.renderNow()
      val scrollback = m
        .container
        .querySelector(".uni-term-scrollback")
        .asInstanceOf[dom.HTMLElement]
      scrollback.style.display shouldBe "none"
      term.write("\u001b[?1049l")
      view.renderNow()
      scrollback.style.display shouldBe ""
      rowTexts(m) shouldBe Seq("a", "b", "c")
    }
  }

  test("keep scrolled-off lines above the screen") {
    val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 2))
    val view = TerminalView(term)
    // A pane two rows tall
    val m = mount(div(styleAttr -> "width: 400px; height: 44px;", view))
    settle().map { _ =>
      term.write("a\r\nb\r\nc\r\nd")
      view.renderNow()
      term.rows shouldBe 2
      rowTexts(m) shouldBe Seq("a", "b", "c", "d")
    }
  }

  test("send keys and typed text to the program") {
    val (_, _, m, sent) = setup()
    settle().map { _ =>
      val textarea = m.get("textarea")
      fireEvent.keyDown(textarea, "ArrowUp") shouldBe false
      fireEvent.keyDown(textarea, "c", ctrlKey = true) shouldBe false
      // Plain characters arrive through the input event, so IME and dead keys work
      fireEvent.keyDown(textarea, "x") shouldBe true
      fireEvent.input(textarea, "x")
      fireEvent.keyDown(textarea, "Enter", isComposing = true)
      sent.toSeq shouldBe Seq("\u001b[A", "\u0003", "x")
    }
  }

  test("treat Alt+letter as Meta using the physical key") {
    val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 3))
    val sent = mutable.ArrayBuffer.empty[String]
    term.onData(sent += _)
    val m = mount(TerminalView(term, TerminalViewConfig().withMacOptionIsMeta(true)))
    settle().map { _ =>
      fireEvent.keyDown(m.get("textarea"), "∫", code = "KeyB", altKey = true)
      sent.toSeq shouldBe Seq("\u001bb")
    }
  }

  test("leave AltGr characters to the input event") {
    val (_, _, m, sent) = setup()
    settle().map { _ =>
      val textarea = m.get("textarea")
      // AltGr+Q types `@` on a German layout; browsers report it as Ctrl+Alt
      val e = dom.KeyboardEvent(
        "keydown",
        js.Dynamic
          .literal(
            key = "@",
            code = "KeyQ",
            ctrlKey = true,
            altKey = true,
            modifierAltGraph = true,
            bubbles = true,
            cancelable = true
          )
          .asInstanceOf[dom.KeyboardEventInit]
      )
      textarea.dispatchEvent(e) shouldBe true
      fireEvent.input(textarea, "@")
      sent.toSeq shouldBe Seq("@")
    }
  }

  test("show the cursor on the cursor cell") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      term.write("ab\u001b[1;2H")
      view.renderNow()
      val cursor = m.container.querySelector(".uni-term-cursor")
      cursor.textContent shouldBe "b"
      term.write("\u001b[?25l")
      view.renderNow()
      m.container.querySelector(".uni-term-cursor") shouldBe null
    }
  }

  private def links(m: Mounted): Seq[dom.Element] =
    val nodes = m.container.querySelectorAll(".uni-term-link")
    (0 until nodes.length).map(nodes(_))

  private def osc8(uri: String, text: String): String =
    s"\u001b]8;;${uri}\u0007${text}\u001b]8;;\u0007"

  test("use Uni's dark theme by default") {
    TerminalViewConfig().theme shouldBe TerminalTheme.uniDark
    TerminalTheme.presets.head shouldBe ("uniDark" -> TerminalTheme.uniDark)
    TerminalTheme.presets.values.forall(_.ansi.size == 16) shouldBe true
  }

  test("switch themes without redrawing rows") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      term.write("\u001b[31mred\u001b[0m")
      view.renderNow()
      val pane = m.container.querySelector(".uni-terminal").asInstanceOf[dom.HTMLElement]
      val row  = m.container.querySelector(".uni-term-row")
      pane.style.getPropertyValue("--uni-term-bg") shouldBe TerminalTheme.uniDark.background
      row.innerHTML shouldContain "var(--uni-term-1)"
      view.setTheme(TerminalTheme.solarizedLight)
      pane.style.getPropertyValue("--uni-term-bg") shouldBe TerminalTheme.solarizedLight.background
      pane.style.getPropertyValue("--uni-term-1") shouldBe TerminalTheme.solarizedLight.ansi(1)
      view.currentTheme shouldBe TerminalTheme.solarizedLight
      // The same row element, not a redrawn one
      (m.container.querySelector(".uni-term-row") eq row) shouldBe true
    }
  }

  test("mark only links with an allowed scheme") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      term.write(osc8("https://wvlet.org", "web") + " " + osc8("javascript:alert(1)", "js"))
      view.renderNow()
      val ls = links(m)
      ls.map(_.textContent) shouldBe Seq("web")
      ls.head.getAttribute("title") shouldContain "+click to open https://wvlet.org"
    }
  }

  test("open links with a modifier click through the handler") {
    val (term, view, m, _) = setup()
    val opened             = mutable.ArrayBuffer.empty[String]
    view.onLinkClick(opened += _)
    settle().map { _ =>
      term.write(osc8("https://wvlet.org/uni", "uni"))
      view.renderNow()
      val link = links(m).head
      fireEvent.click(link)
      opened.toSeq shouldBe Seq.empty
      fireEvent.click(link, ctrlKey = true, metaKey = true) shouldBe false
      opened.toSeq shouldBe Seq("https://wvlet.org/uni")
    }
  }

  test("open links with window.open when no handler is set") {
    val (term, view, m, _) = setup()
    val opened             = mutable.ArrayBuffer.empty[(String, String)]
    val win                = dom.window.asInstanceOf[js.Dynamic]
    val original           = win.open
    win.open =
      (
          (uri: String, target: String, features: String) =>
            opened += (uri -> features)
            null
      ): js.Function3[String, String, String, Any]
    settle().map { _ =>
      try
        term.write(osc8("mailto:dev@example.com", "mail"))
        view.renderNow()
        fireEvent.click(links(m).head, ctrlKey = true, metaKey = true)
        opened.toSeq shouldBe Seq("mailto:dev@example.com" -> "noopener,noreferrer")
      finally
        win.open = original
    }
  }

  test("show links as plain text with noLinks") {
    val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 3))
    val view = TerminalView(term, TerminalViewConfig().noLinks())
    val m    = mount(div(styleAttr -> "width: 400px; height: 200px;", view))
    settle().map { _ =>
      term.write(osc8("https://wvlet.org", "web"))
      view.renderNow()
      links(m) shouldBe Seq.empty
      rowTexts(m).head shouldBe "web"
    }
  }

  test("show the open affordance while the modifier is held") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      val pane = m.container.querySelector(".uni-terminal")
      fireEvent.mouseMove(pane, ctrlKey = true, metaKey = true)
      pane.classList.contains("uni-term-link-mod") shouldBe true
      fireEvent.mouseMove(pane)
      pane.classList.contains("uni-term-link-mod") shouldBe false
    }
  }

  test("draw block and line characters to fill the cell") {
    TerminalGlyphs.css('A'.toInt, 8, 16) shouldBe ""
    TerminalGlyphs.css(0x256d, 8, 16) shouldBe "" // rounded corners keep the font glyph
    val full = TerminalGlyphs.css(0x2588, 8, 16)
    full shouldContain "width: 8.00px; height: 16.00px"
    full shouldContain "background-size: 8.00px 16.00px"
    // Upper left, upper right, and lower left quadrants
    TerminalGlyphs.css(0x259b, 8, 16).split("linear-gradient").length shouldBe 4
    TerminalGlyphs.css(0x2591, 8, 16) shouldContain "currentColor 25%"
    // A horizontal line spans the cell at its vertical center
    TerminalGlyphs.css(0x2500, 8, 16) shouldContain "background-position: 0.00px 8.00px"
  }

  test("keep drawn block characters as text for copying") {
    val (term, view, m, _) = setup()
    settle().map { _ =>
      term.write("a\u2590\u259b\u2588b")
      view.renderNow()
      rowTexts(m).head shouldBe "a\u2590\u259b\u2588b"
      val spans  = m.container.querySelectorAll(".uni-term-row span")
      val blocks = (0 until spans.length)
        .map(i => spans(i).asInstanceOf[dom.HTMLElement])
        .filter(_.style.display == "inline-block")
      blocks.map(_.textContent) shouldBe Seq("\u2590", "\u259b", "\u2588")
      val row = m.container.querySelector(".uni-term-row").asInstanceOf[dom.HTMLElement]
      blocks.forall(_.getBoundingClientRect().height == row.getBoundingClientRect().height) shouldBe
        true
    }
  }

end TerminalViewTest
