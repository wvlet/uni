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

import wvlet.uni.test.UniTest

import scala.collection.mutable

class TerminalCommandTest extends UniTest:
  private def term(cols: Int = 20, rows: Int = 6, scrollback: Int = 100): TerminalEmulator =
    TerminalEmulator(TerminalConfig(cols, rows, scrollback))

  // What a shell with OSC 133 integration prints
  private val A            = "\u001b]133;A\u0007"
  private val B            = "\u001b]133;B\u0007"
  private val C            = "\u001b]133;C\u0007"
  private def D(code: Int) = s"\u001b]133;D;${code}\u0007"
  private def prompt       = s"${A}$$ ${B}"
  private def run(command: String, output: String, code: Int): String =
    s"${command}\r\n${C}${output}${D(code)}"

  test("find commands, their output, and exit codes") {
    val t = term()
    t.write(
      prompt + run("echo hi", "hi\r\n", 0) + prompt + run("ls x", "ls: x: No such file\r\n", 1) +
        prompt
    )
    t.commands shouldBe
      Seq(
        TerminalCommand(0, "echo hi", "hi", Some(0)),
        TerminalCommand(2, "ls x", "ls: x: No such file", Some(1))
      )
    t.commands(1).isFailed shouldBe true
    t.screen.line(4).isPromptStart shouldBe true
  }

  test("a command without output ends on the next prompt's line") {
    val t = term()
    t.write(prompt + run("false", "", 1) + prompt + run("true", "", 0) + prompt)
    t.commands shouldBe
      Seq(TerminalCommand(0, "false", "", Some(1)), TerminalCommand(1, "true", "", Some(0)))
  }

  test("an exit code that arrives after the next prompt still goes to the finished command") {
    val t = term()
    t.write(prompt + s"false\r\n${C}" + prompt + D(2))
    t.commands shouldBe Seq(TerminalCommand(0, "false", "", Some(2)))
  }

  test("a running command has no exit code, and an empty command line is not a command") {
    val t = term()
    t.write(prompt + "\r\n" + prompt + s"sleep 5\r\n${C}")
    t.commands shouldBe Seq(TerminalCommand(1, "sleep 5", "", None))
    t.commands.head.isFinished shouldBe false
  }

  test("join wrapped output lines and keep line breaks") {
    val t = term(cols = 10)
    t.write(prompt + run("cat f", "0123456789abc\r\nline2\r\n", 0) + prompt)
    t.commands.head.output shouldBe "0123456789abc\nline2"
  }

  test("prefer the command line the shell reported with cmdline_url") {
    val t = term()
    t.write(
      s"${A}$$ ${B}echo 100%           [rprompt]\r\n\u001b]133;C;cmdline_url=echo%20100%25%3B%20%E6%97%A5\u0007" +
        D(0)
    )
    t.commands.head.command shouldBe "echo 100%; 日"
  }

  test("notify finished commands") {
    val t        = term()
    val finished = mutable.ArrayBuffer.empty[TerminalCommand]
    t.onCommandFinished(finished += _)
    t.write(prompt + run("make", "error\r\n", 2) + prompt)
    finished.toSeq shouldBe Seq(TerminalCommand(0, "make", "error", Some(2)))
  }

  test("commands survive scrolling into the scrollback and a rewrap") {
    val t = term(cols = 20, rows = 3)
    t.write(prompt + run("echo a", "a\r\nb\r\nc\r\n", 1) + prompt)
    t.screen.scrollback.size shouldBe 2
    t.commands shouldBe Seq(TerminalCommand(0, "echo a", "a\nb\nc", Some(1)))
    t.resize(4, 3)
    t.commands.map(c => (c.command, c.output, c.exitCode)) shouldBe
      Seq(("echo a", "a\nb\nc", Some(1)))
  }

  test("an exit code for a prompt in the scrollback changes marksVersion") {
    val t = term(cols = 20, rows = 2)
    t.write(prompt + s"seq 3\r\n${C}1\r\n2\r\n3\r\n")
    val version = t.screen.marksVersion
    t.write(D(0))
    (t.screen.marksVersion > version) shouldBe true
    t.screen.scrollback(0).exitCode shouldBe Some(0)
  }

  test("ignore marks on the alternate screen") {
    val t = term()
    t.write("\u001b[?1049h" + prompt + run("x", "", 0) + "\u001b[?1049l")
    t.commands shouldBe Seq.empty
  }

  test("decode percent escapes as UTF-8") {
    TerminalCommand.percentDecode("a%20b%E6%97%A5%zz%") shouldBe "a b日%zz%"
  }

end TerminalCommandTest
