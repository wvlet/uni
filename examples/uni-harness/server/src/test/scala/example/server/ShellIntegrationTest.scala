package example.server

import wvlet.uni.cli.term.PtyOptions
import wvlet.uni.test.UniTest

class ShellIntegrationTest extends UniTest:
  test("name the shell from its path") {
    ShellIntegration.shellName("/bin/zsh") shouldBe "zsh"
    ShellIntegration.shellName("/opt/homebrew/bin/bash") shouldBe "bash"
    ShellIntegration.shellName("C:\\Windows\\System32\\cmd.exe") shouldBe "cmd"
  }

  test("start zsh with a ZDOTDIR that loads the hooks, and bash with an rcfile") {
    val (zshArgs, zsh) = ShellIntegration.wrap("/bin/zsh", Seq.empty, PtyOptions())
    zshArgs shouldBe Seq.empty
    zsh.env.get("ZDOTDIR").exists(_.contains("uni-harness-shell-")) shouldBe true
    val (bashArgs, _) = ShellIntegration.wrap("/bin/bash", Seq.empty, PtyOptions())
    bashArgs.take(1) shouldBe Seq("--rcfile")
    bashArgs(1).endsWith("/bashrc") shouldBe true
  }

  test("start other shells unchanged") {
    val options = PtyOptions()
    ShellIntegration.wrap("/usr/bin/fish", Seq("-l"), options) shouldBe (Seq("-l"), options)
  }

  test("emit every OSC 133 mark from the zsh and bash hooks") {
    for mark <- Seq("133;A", "133;B", "133;C", "133;D") do
      ShellIntegration.zshrc shouldContain mark
    for mark <- Seq("133;A", "133;B", "133;C", "133;D") do
      ShellIntegration.bashrc shouldContain mark
  }
