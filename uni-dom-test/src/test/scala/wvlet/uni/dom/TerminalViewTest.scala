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

end TerminalViewTest
