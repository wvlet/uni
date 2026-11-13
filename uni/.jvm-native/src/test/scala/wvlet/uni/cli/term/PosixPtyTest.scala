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

import java.util.concurrent.atomic.AtomicReference

/**
  * Runs programs in real PTYs. Every wait is bounded, and on platforms without PTYs (Windows, Java
  * before 22) the tests are reported as skipped, not passed.
  */
class PosixPtyTest extends UniTest:
  private final val TimeoutMillis = 10000L

  /**
    * A started program, with its output collected
    */
  private class Run(val process: PtyProcess):
    private val output = StringBuffer()
    private val exit   = AtomicReference[Option[Int]](None)
    process.onData(d => output.append(d))
    process.onExit(code => exit.set(Some(code)))

    def text: String = output.toString

    def awaitOutput(expected: String): Unit =
      await(s"output containing ${expected}, got: ${text}") {
        text.contains(expected)
      }

    def awaitExit(): Int =
      await(s"the program to exit, got: ${text}")(exit.get.isDefined)
      exit.get.get

  private def await(what: String)(cond: => Boolean): Unit =
    val deadline = System.currentTimeMillis() + TimeoutMillis
    while !cond && System.currentTimeMillis() < deadline do
      Thread.sleep(10)
    if !cond then
      fail(s"Timed out after ${TimeoutMillis} ms waiting for ${what}")

  private def sh(script: String, options: PtyOptions = PtyOptions()): Run =
    PosixPty.unsupportedReason.foreach(reason => skip(reason))
    Run(PosixPty.spawn("sh", Seq("-c", script), options))

  test("the program sees a terminal of the initial size") {
    val r = sh("test -t 0 && test -t 1 && echo tty:yes; stty size", PtyOptions().withSize(30, 7))
    r.awaitExit() shouldBe 0
    r.text shouldContain "tty:yes"
    r.text shouldContain "7 30"
  }

  test("resize reaches the program through SIGWINCH") {
    // SIGWINCH goes to the terminal's foreground process group, so this also checks that the PTY
    // is the program's controlling terminal
    val r = sh("trap 'stty size; exit 5' WINCH; echo ready; while :; do sleep 0.05; done")
    r.awaitOutput("ready")
    r.process.resize(100, 40)
    r.awaitExit() shouldBe 5
    r.text shouldContain "40 100"
  }

  test("input reaches the program and output comes back") {
    val r = sh("read line; echo \"got:${line}\"")
    r.process.write("héllo 日本\r")
    r.awaitExit() shouldBe 0
    r.text shouldContain "got:héllo 日本"
  }

  test("a character split across reads arrives whole") {
    // あ is E3 81 82; the pause makes the PTY deliver its bytes in two reads
    val r = sh("printf '\\343\\201'; sleep 0.3; printf '\\202:done'")
    r.awaitExit() shouldBe 0
    r.text shouldContain "あ:done"
    r.text.contains("�") shouldBe false
  }

  test("the environment, TERM, and working directory reach the program") {
    val r = sh(
      "echo \"${TERM}:${UNI_PTY_TEST}\"; pwd -P",
      PtyOptions().withEnv(Map("UNI_PTY_TEST" -> "on")).withCwd("/").withTermName("xterm")
    )
    r.awaitExit() shouldBe 0
    r.text shouldContain "xterm:on"
    r.text.split("\r?\n").map(_.trim) shouldContain "/"
  }

  test("report exit codes") {
    sh("exit 0").awaitExit() shouldBe 0
    sh("exit 3").awaitExit() shouldBe 3
    // A program ended by a signal reports 128 + the signal number, as shells do (SIGTERM is 15)
    sh("kill -TERM $$").awaitExit() shouldBe 143
  }

  test("kill sends SIGHUP and is safe to repeat") {
    val r = sh("echo ready; while :; do sleep 0.05; done")
    r.awaitOutput("ready")
    r.process.kill()
    r.process.kill()
    r.awaitExit() shouldBe 129
    // After the exit, these do nothing
    r.process.kill()
    r.process.write("ignored")
    r.process.resize(10, 10)
  }

  test("write does not block when the program does not read") {
    val r     = sh("sleep 0.5")
    val start = System.currentTimeMillis()
    r.process.write("x" * (1 << 20))
    (System.currentTimeMillis() - start < 1000) shouldBe true
    r.awaitExit() shouldBe 0
  }

  test("output before the first listener is kept") {
    PosixPty.unsupportedReason.foreach(reason => skip(reason))
    val p = PosixPty.spawn("sh", Seq("-c", "echo early"))
    Thread.sleep(300)
    val r = Run(p)
    r.awaitExit() shouldBe 0
    r.text shouldContain "early"
  }

  test("an exit listener added after the exit is still called") {
    val r = sh("exit 7")
    r.awaitExit() shouldBe 7
    val late = AtomicReference[Option[Int]](None)
    r.process.onExit(code => late.set(Some(code)))
    late.get shouldBe Some(7)
  }

  test("connect a TerminalEmulator to a program") {
    PosixPty.unsupportedReason.foreach(reason => skip(reason))
    val terminal = TerminalEmulator(TerminalConfig().withSize(40, 5))
    // The program asks for the cursor position (DSR 6), and the emulator's reply travels back
    // through the PTY: ESC [ 1 ; 6 R
    val p = PosixPty.spawn(
      "sh",
      Seq(
        "-c",
        "stty raw -echo; printf '\\033[2J\\033[Hhello\\033[6n'; " +
          "r=$(dd bs=1 count=6 2>/dev/null); stty sane; printf '\\r\\nreply:%s' \"${r}\" | tr '\\033' E"
      )
    )
    // Connect first: output that arrives before any listener goes to the first one only
    PtyProcess.connect(terminal, p)
    val r = Run(p)
    r.awaitExit() shouldBe 0
    r.text shouldContain "reply:E[1;6R"
    // Output is delivered before the exit is reported
    terminal.screen.lineText(0).trim shouldBe "hello"
  }

  test("a missing program fails to start") {
    PosixPty.unsupportedReason.foreach(reason => skip(reason))
    intercept[java.io.IOException] {
      PosixPty.spawn("uni-pty-test-no-such-program")
    }
  }

end PosixPtyTest
