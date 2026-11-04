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

import scala.collection.mutable

class TerminalEmulatorTest extends UniTest:
  private def term(cols: Int = 10, rows: Int = 4, scrollback: Int = 100): TerminalEmulator =
    TerminalEmulator(TerminalConfig(cols, rows, scrollback))

  private def screenOf(output: String, cols: Int = 10, rows: Int = 4): Screen =
    val t = term(cols, rows)
    t.write(output)
    t.screen

  private def cursor(s: Screen): (Int, Int) = (s.cursorRow, s.cursorCol)

  test("print text with carriage return and line feed") {
    val s = screenOf("hello\r\nworld")
    s.text shouldBe "hello\nworld"
    cursor(s) shouldBe (1, 5)
  }

  test("wrap at the right margin, marking the continued line") {
    val s = screenOf("0123456789ab")
    s.text shouldBe "0123456789\nab"
    s.line(1).isWrapped shouldBe true
  }

  test("hold the wrap until the next character arrives") {
    val s = screenOf("0123456789")
    cursor(s) shouldBe (0, 9)
    s.text shouldBe "0123456789"
    val t = term()
    t.write("0123456789\r\nx")
    t.screen.text shouldBe "0123456789\nx"
  }

  test("scroll into the scrollback") {
    val t = term(rows = 2)
    t.write("a\r\nb\r\nc\r\nd")
    t.screen.text shouldBe "c\nd"
    t.screen.scrollback.size shouldBe 2
    t.screen.scrollback(0).text shouldBe "a"
    t.screen.scrollbackTotal shouldBe 2L
  }

  test("move the cursor and erase") {
    val s = screenOf("abcdef\r\nghijkl\u001b[1;3H\u001b[K")
    s.text shouldBe "ab\nghijkl"
    val s2 = screenOf("abcdef\r\nghijkl\u001b[2;3H\u001b[1J")
    s2.text shouldBe "\n   jkl"
    val s3 = screenOf("abc\r\ndef\u001b[2J")
    s3.text shouldBe ""
  }

  test("insert and delete characters") {
    screenOf("abcdef\u001b[1;2H\u001b[2@").text shouldBe "a  bcdef"
    screenOf("abcdef\u001b[1;2H\u001b[2P").text shouldBe "adef"
    screenOf("abcdef\u001b[1;2H\u001b[2X").text shouldBe "a  def"
  }

  test("insert and delete lines inside the scroll region") {
    val s = screenOf("1\r\n2\r\n3\r\n4\u001b[2;1H\u001b[L")
    s.text shouldBe "1\n\n2\n3"
    val s2 = screenOf("1\r\n2\r\n3\r\n4\u001b[2;1H\u001b[M")
    s2.text shouldBe "1\n3\n4"
  }

  test("scroll only inside the scroll region") {
    val t = term(rows = 4)
    t.write("top\r\n\u001b[2;3r\u001b[3;1Ha\r\nb\r\nc")
    t.screen.text shouldBe "top\nb\nc"
    // Scrolling a region that does not start at the top keeps the scrollback empty
    t.screen.scrollback.size shouldBe 0
  }

  test("apply SGR colors and attributes") {
    val s    = screenOf("\u001b[1;31mR\u001b[0;38;5;200mP\u001b[38;2;1;2;3;48;5;4mT\u001b[0mn")
    val line = s.line(0)
    line.fg(0) shouldBe CellColor.palette(1)
    (line.flags(0) & CellFlags.Bold) shouldNotBe 0
    line.fg(1) shouldBe CellColor.palette(200)
    (line.flags(1) & CellFlags.Bold) shouldBe 0
    line.fg(2) shouldBe CellColor.rgb(1, 2, 3)
    line.bg(2) shouldBe CellColor.palette(4)
    line.fg(3) shouldBe CellColor.Default
  }

  test("apply colon-form truecolor and bright colors") {
    val s = screenOf("\u001b[38:2::10:20:30mA\u001b[97;104mB")
    s.line(0).fg(0) shouldBe CellColor.rgb(10, 20, 30)
    s.line(0).fg(1) shouldBe CellColor.palette(15)
    s.line(0).bg(1) shouldBe CellColor.palette(12)
  }

  test("erase with the current background color") {
    val s = screenOf("abc\u001b[44m\u001b[1;1H\u001b[K")
    s.line(0).bg(0) shouldBe CellColor.palette(4)
    s.line(0).bg(9) shouldBe CellColor.palette(4)
  }

  test("place wide characters in two cells") {
    val s = screenOf("a日b")
    s.line(0).text shouldBe "a日b"
    (s.line(0).flags(1) & CellFlags.Wide) shouldNotBe 0
    (s.line(0).flags(2) & CellFlags.WideTail) shouldNotBe 0
    s.cursorCol shouldBe 4
  }

  test("wrap a wide character that does not fit at the margin") {
    val s = screenOf("012345678日")
    s.text shouldBe "012345678\n日"
  }

  test("overwriting half of a wide character blanks the other half") {
    val s = screenOf("日本\u001b[1;2Hx")
    s.line(0).text shouldBe " x本"
  }

  test("join combining marks to the previous character") {
    val s = screenOf("éx")
    s.line(0).cellText(0) shouldBe "é"
    s.line(0).text shouldBe "éx"
  }

  test("switch to the alternate screen and back") {
    val t = term()
    t.write("shell\u001b[?1049h\u001b[Hvim")
    t.screen.isAlternate shouldBe true
    t.screen.text shouldBe "vim"
    t.write("\u001b[?1049l")
    t.screen.isAlternate shouldBe false
    t.screen.text shouldBe "shell"
    cursor(t.screen) shouldBe (0, 5)
  }

  test("save and restore the cursor") {
    val s = screenOf("ab\u001b7\u001b[3;5Hx\u001b8y")
    s.line(0).text shouldBe "aby"
  }

  test("draw DEC line-drawing characters") {
    screenOf("\u001b(0lqk\u001b(Bx").line(0).text shouldBe "┌─┐x"
  }

  test("tab to the next tab stop") {
    val t = term(cols = 20)
    t.write("a\tb")
    t.screen.line(0).text shouldBe "a       b"
  }

  test("repeat the last character") {
    screenOf("x\u001b[3b").line(0).text shouldBe "xxxx"
  }

  test("reply to cursor position and device attribute queries") {
    val t       = term()
    val replies = mutable.ArrayBuffer.empty[String]
    t.onData(replies += _)
    t.write("ab\r\nc\u001b[6n\u001b[c\u001b[5n")
    replies.toSeq shouldBe Seq("\u001b[2;2R", "\u001b[?1;2c", "\u001b[0n")
  }

  test("set the title and track hyperlinks") {
    val t      = term()
    val titles = mutable.ArrayBuffer.empty[String]
    t.onTitleChange(titles += _)
    t.write("\u001b]0;my title\u0007\u001b]8;;https://example.com\u001b\\link\u001b]8;;\u001b\\ x")
    t.title shouldBe "my title"
    titles.toSeq shouldBe Seq("my title")
    val id = t.screen.line(0).link(0)
    t.hyperlink(id) shouldBe Some("https://example.com")
    t.screen.line(0).link(5) shouldBe 0
  }

  test("track input modes") {
    val t = term()
    t.write("\u001b[?1h\u001b[?2004h\u001b[?1002h\u001b[?1006h")
    t.inputModes shouldBe
      InputModes(
        applicationCursorKeys = true,
        bracketedPaste = true,
        mouseTracking = MouseTracking.Drag,
        sgrMouse = true
      )
  }

  test("hold change notifications during synchronized output") {
    val t       = term()
    var changes = 0
    t.onChange(() => changes += 1)
    t.write("\u001b[?2026h")
    t.write("frame")
    changes shouldBe 0
    t.write("\u001b[?2026l")
    changes shouldBe 1
  }

  test("track the cursor shape and visibility") {
    val s = screenOf("\u001b[6 q\u001b[?25l")
    s.cursorShape shouldBe CursorStyle.Bar
    s.isCursorBlinking shouldBe false
    s.isCursorVisible shouldBe false
  }

  test("resize keeps content and pushes rows above the cursor into the scrollback") {
    val t = term(cols = 10, rows = 4)
    t.write("1\r\n2\r\n3\r\n4")
    t.resize(5, 2)
    t.screen.text shouldBe "3\n4"
    t.screen.scrollback.size shouldBe 2
    cursor(t.screen) shouldBe (1, 1)
    t.resize(5, 4)
    t.screen.text shouldBe "1\n2\n3\n4"
    cursor(t.screen) shouldBe (3, 1)
  }

  test("shrinking drops empty rows below the cursor first") {
    val t = term(rows = 4)
    t.write("a")
    t.resize(10, 2)
    t.screen.text shouldBe "a"
    t.screen.scrollback.size shouldBe 0
  }

  test("reset to the power-on state") {
    val t = term()
    t.write("abc\u001b[?1h\u001bc")
    t.screen.text shouldBe ""
    t.inputModes shouldBe InputModes()
  }

  test("send encoded input through onData") {
    val t    = term()
    val sent = mutable.ArrayBuffer.empty[String]
    t.onData(sent += _)
    t.sendKey(Key.Up)
    t.write("\u001b[?1h")
    t.sendKey(Key.Up)
    t.sendKey("c", ctrl = true) shouldBe true
    t.sendKey("Shift") shouldBe false
    sent.toSeq shouldBe Seq("\u001b[A", "\u001bOA", "\u0003")
  }

  test("track dirty rows") {
    val t = term()
    t.screen.clearDirty()
    t.write("\u001b[3;1Hx")
    (0 until t.rows).map(t.screen.isDirty) shouldBe Seq(false, false, true, false)
  }

  test("a 0 bottom margin extends the scroll region to the last row") {
    val t = term(rows = 4)
    t.write("1\r\n2\r\n3\r\n4\u001b[2;0r\u001b[4;1H\n")
    t.screen.text shouldBe "1\n3\n4"
    t.screen.scrollback.size shouldBe 0
  }

  test("track scrollback changes separately from screen redraws") {
    val t = term(rows = 2)
    t.write("a\r\nb\r\nc")
    val version = t.screen.scrollbackVersion
    t.write("\u001b[?1049h\u001b[?1049l")
    t.screen.scrollbackVersion shouldBe version
    t.write("\u001b[3J")
    (t.screen.scrollbackVersion > version) shouldBe true
  }

end TerminalEmulatorTest
