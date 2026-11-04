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
package wvlet.uni.cli.term

import wvlet.uni.cli.Key
import wvlet.uni.test.UniTest

class InputEncoderTest extends UniTest:
  private def encoder(modes: InputModes = InputModes()): InputEncoder = InputEncoder(() => modes)

  test("encode keys by DOM key name") {
    val e = encoder()
    e.key("a") shouldBe Some("a")
    e.key("Enter") shouldBe Some("\r")
    e.key("Backspace") shouldBe Some("\u007f")
    e.key("ArrowLeft") shouldBe Some("\u001b[D")
    e.key("ArrowLeft", ctrl = true) shouldBe Some("\u001b[1;5D")
    e.key("ArrowUp", shift = true) shouldBe Some("\u001b[1;2A")
    e.key("Delete") shouldBe Some("\u001b[3~")
    e.key("PageDown", alt = true) shouldBe Some("\u001b[6;3~")
    e.key("F1") shouldBe Some("\u001bOP")
    e.key("F5") shouldBe Some("\u001b[15~")
    e.key("F12", shift = true) shouldBe Some("\u001b[24;2~")
    e.key("Tab", shift = true) shouldBe Some("\u001b[Z")
    e.key("x", alt = true) shouldBe Some("\u001bx")
    e.key("日") shouldBe Some("日")
    e.key("Shift") shouldBe None
  }

  test("encode control characters") {
    val e = encoder()
    e.key("c", ctrl = true) shouldBe Some("\u0003")
    e.key("C", ctrl = true) shouldBe Some("\u0003")
    e.key("[", ctrl = true) shouldBe Some("\u001b")
    e.key(" ", ctrl = true) shouldBe Some("\u0000")
  }

  test("encode cursor keys in application mode") {
    val e = encoder(InputModes(applicationCursorKeys = true))
    e.key("ArrowUp") shouldBe Some("\u001bOA")
    e.key(Key.Home) shouldBe "\u001bOH"
  }

  test("encode uni Key values") {
    val e = encoder()
    e.key(Key.Ctrl('a')) shouldBe "\u0001"
    e.key(Key.Alt(Key.char('b'))) shouldBe "\u001bb"
    e.key(Key.Enter) shouldBe "\r"
  }

  test("paste with and without bracketed paste") {
    encoder().paste("a\nb\r\nc") shouldBe "a\rb\rc"
    encoder(InputModes(bracketedPaste = true)).paste("x\u001b[201~y") shouldBe
      "\u001b[200~xy\u001b[201~"
  }

  test("encode mouse events in SGR and X10 form") {
    val press = MouseEvent(MouseAction.Press, MouseButton.Left, row = 2, col = 4)
    encoder().mouse(press) shouldBe None
    val sgr = encoder(InputModes(mouseTracking = MouseTracking.Click, sgrMouse = true))
    sgr.mouse(press) shouldBe Some("\u001b[<0;5;3M")
    sgr.mouse(press.copy(action = MouseAction.Release)) shouldBe Some("\u001b[<0;5;3m")
    sgr.mouse(press.copy(action = MouseAction.Move)) shouldBe None
    sgr.mouse(press.copy(button = MouseButton.WheelUp)) shouldBe Some("\u001b[<64;5;3M")
    val x10 = encoder(InputModes(mouseTracking = MouseTracking.Click))
    x10.mouse(press) shouldBe Some("\u001b[M" + 32.toChar + 37.toChar + 35.toChar)
    val drag = encoder(InputModes(mouseTracking = MouseTracking.Drag, sgrMouse = true))
    drag.mouse(press.copy(action = MouseAction.Move)) shouldBe Some("\u001b[<32;5;3M")
    drag.mouse(press.copy(action = MouseAction.Move, button = MouseButton.None)) shouldBe None
  }

end InputEncoderTest
