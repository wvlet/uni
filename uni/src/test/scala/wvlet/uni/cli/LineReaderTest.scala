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
package wvlet.uni.cli

import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

class LineReaderTest extends UniTest:
  private val Left  = "\u001b[D"
  private val Up    = "\u001b[A"
  private val CtrlA = "\u0001"
  private val CtrlC = "\u0003"
  private val CtrlD = "\u0004"
  private val CtrlK = "\u000b"
  private val CtrlR = "\u0012"
  private val CtrlY = "\u0019"

  private def terminal(chunks: String*): InMemoryTerminalIO = TerminalIO.inMemory(
    chunks.map(_.getBytes("UTF-8"))
  )

  private def reader(io: TerminalIO, config: LineReaderConfig = LineReaderConfig()): LineReader =
    LineReader(config, io)

  /**
    * Read inputs one after another and collect the results
    */
  private def readAll(r: LineReader, n: Int): Rx[Seq[LineResult]] = (0 until n).foldLeft(
    Rx.single(Seq.empty[LineResult])
  ) { (acc, _) =>
    acc.flatMap(results => r.readLine("> ").map(results :+ _))
  }

  test("read a line") {
    val io = terminal("hello\r")
    reader(io)
      .readLine("> ")
      .map { result =>
        result shouldBe LineResult.Line("hello")
        io.output shouldContain "> hello"
        io.isRawMode shouldBe false
      }
  }

  test("edit with cursor movement, kill, and yank") {
    val io = terminal(s"abc${Left}${Left}\u007fX", s"${CtrlA}${CtrlK}${CtrlY}${CtrlY}\r")
    reader(io).readLine("> ").map(_ shouldBe LineResult.Line("XbcXbc"))
  }

  test("insert wide characters") {
    val io = terminal(s"日本${Left}語\r")
    reader(io).readLine("> ").map(_ shouldBe LineResult.Line("日語本"))
  }

  test("continue input until it is complete") {
    val io     = terminal("select 1\r", "  from t;\r")
    val config = LineReaderConfig().withInputComplete(_.trim.endsWith(";"))
    reader(io, config).readLine("> ").map(_ shouldBe LineResult.Line("select 1\n  from t;"))
  }

  test("keep pasted multi-line text in one input") {
    val io = terminal("\u001b[200~a\nb\u001b[201~\r")
    reader(io).readLine("> ").map(_ shouldBe LineResult.Line("a\nb"))
  }

  test("read lines typed ahead in one chunk") {
    val io = terminal("first\rsecond\r")
    readAll(reader(io), 2).map(_ shouldBe Seq(LineResult.Line("first"), LineResult.Line("second")))
  }

  test("recall history with Up and reverse search") {
    val io = terminal("select 1\r", "show tables\r", s"${Up}${Up}\r", s"${CtrlR}tab\r")
    val r  = reader(io)
    readAll(r, 4).map { results =>
      results.drop(2) shouldBe Seq(LineResult.Line("select 1"), LineResult.Line("show tables"))
      r.history.toSeq shouldBe Seq("select 1", "show tables", "select 1", "show tables")
    }
  }

  test("complete words with Tab") {
    val io     = terminal("sel\t\r", "s\t\r")
    val config = LineReaderConfig().withCompleter(Completer.words(Seq("select", "show")))
    readAll(reader(io, config), 2).map { results =>
      results shouldBe Seq(LineResult.Line("select "), LineResult.Line("s"))
      // Ambiguous candidates are listed above the prompt
      io.output shouldContain "select  show"
    }
  }

  test("run a custom action bound to a chord") {
    val acceptWithSemicolon = LineAction.Custom(
      "accept-statement",
      editor =>
        editor.moveCursor(editor.buffer.length)
        editor.insert(";")
        editor.accept()
    )
    val config = LineReaderConfig()
      .withKeyMap(KeyMap.emacs.unbind("^J"))
      .bind("^J^R", acceptWithSemicolon)
    // ^J followed by a key that does not finish the chord runs both keys separately
    val io = terminal(s"select 1${CtrlA}\n\u0012", "x\nb\r")
    readAll(reader(io, config), 2).map(
      _ shouldBe Seq(LineResult.Line("select 1;"), LineResult.Line("xb"))
    )
  }

  test("report Ctrl-C and Ctrl-D") {
    val io = terminal(s"ab${CtrlC}", CtrlD)
    readAll(reader(io), 2).map(_ shouldBe Seq(LineResult.Interrupted("ab"), LineResult.EndOfInput))
  }

  test("report the end of input") {
    val io = terminal("partial")
    readAll(reader(io), 2).map(_ shouldBe Seq(LineResult.Line("partial"), LineResult.EndOfInput))
  }

  test("read piped input line by line without drawing") {
    val io = TerminalIO.inMemory(Seq("a\tb\nc\n".getBytes("UTF-8")), isTTY = false)
    readAll(reader(io), 3).map { results =>
      results shouldBe Seq(LineResult.Line("a\tb"), LineResult.Line("c"), LineResult.EndOfInput)
      io.output shouldBe ""
    }
  }

  test("place the cursor after the prompt and the text before it") {
    val io = terminal(s"ab${Left}", "\r")
    reader(io)
      .readLine("> ")
      .map { _ =>
        // After Left, the cursor is at column 3: "> a|b"
        io.output shouldContain "> ab\r\u001b[3C"
      }
  }

  test("wrap long input and move up to the first row on redraw") {
    val io = TerminalIO.inMemory(
      Seq("abcdefgh".getBytes("UTF-8"), "\r".getBytes("UTF-8")),
      size = TerminalSize(10, 5)
    )
    reader(io)
      .readLine("> ")
      .map { _ =>
        // "> abcdefgh" fills the 10-column row exactly, so the cursor moves to the next row, and
        // the next redraw goes back up one row
        io.output shouldContain "> abcdefgh\r\n"
        io.output shouldContain "\u001b[1A\r\u001b[0J"
      }
  }

  test("keep the cursor position with highlighted text") {
    val io     = terminal(s"select${Left}", "\r")
    val config = LineReaderConfig().withHighlighter(_.replace("select", Tint.blue("select")))
    reader(io, config)
      .readLine("> ")
      .map { _ =>
        io.output shouldContain Tint.blue("select")
        io.output shouldContain "\r\u001b[7C"
      }
  }

  test("print messages above the input") {
    val io = terminal("abc")
    val r  = reader(io)
    r.printAbove("not reading")
    io.output shouldBe "not reading\n"
  }

end LineReaderTest

class LineHistoryTest extends UniTest:
  test("escape multi-line entries") {
    val entry = "select 1\nfrom t\\x"
    LineHistory.escape(entry) shouldBe "select 1\\nfrom t\\\\x"
    LineHistory.unescape(LineHistory.escape(entry)) shouldBe entry
  }

  test("read JLine history lines") {
    LineHistory.unescape("1730000000000:select 1") shouldBe "select 1"
  }

  test("skip blank and repeated entries, and keep the newest") {
    val h = LineHistory(maxSize = 2)
    Seq("a", "a", " ", "b", "c").foreach(h.add)
    h.toSeq shouldBe Seq("b", "c")
  }

class DisplayWidthTest extends UniTest:
  test("count columns") {
    DisplayWidth.of("abc") shouldBe 3
    DisplayWidth.of("日本語") shouldBe 6
    DisplayWidth.of("é") shouldBe 1
    DisplayWidth.of(Tint.red("red")) shouldBe 3
  }

  test("format candidates in columns") {
    LineEditor.formatColumns(Seq("a", "bb", "c"), 10) shouldBe "a   c\nbb"
  }
