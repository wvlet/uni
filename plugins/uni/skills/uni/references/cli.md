# CLI: launcher, colors, progress

`wvlet.uni.cli` (colors, progress bars, spinners) and `wvlet.uni.cli.launcher`
(argument parsing) are in `uni` and compile on JVM, Scala.js, and Scala Native.

Full reference: https://wvlet.org/uni/cli/launcher.md, https://wvlet.org/uni/cli/tint.md,
https://wvlet.org/uni/cli/progress.md

## Options from a case class

```scala
import wvlet.uni.cli.launcher.*

case class MyApp(
    @option(prefix = "-n,--name", description = "User name")
    name: String = "world",
    @option(prefix = "-c,--count", description = "Repeat count")
    count: Int = 1,
    @option(prefix = "-v,--verbose", description = "Verbose output")
    verbose: Boolean = false,
    @option(prefix = "-D", description = "Properties")
    props: Seq[KeyValue] = Seq.empty,         // -Dk=v -Dk2=v2
    @argument(name = "files", description = "Input files")
    files: Seq[String] = Seq.empty            // positional
)

@main def hello(args: String*): Unit =
  val app = Launcher.execute[MyApp](args.toArray) // null when help was shown
  if app != null then
    for _ <- 1 to app.count do println(s"Hello, ${app.name}!")
```

- Accepted forms: `--count 3`, `--count=3`, `-c 3` (not `-c3`); a `Boolean`
  option is a flag. `Option[A]` and `Seq[A]` (repeat the option) are supported.
- `-h`/`--help` prints help for the command it follows; `Launcher.execute[A]`
  then returns `null`. `.withHelpPrefixes(Seq("-h", "--help", "-?"))` changes
  the flags.
- `Launcher.of[A].execute(args)` returns a `LauncherResult(instance,
  executedMethod, showedHelp)`; `execute("--name x")` also takes one string.

## Sub-commands

```scala
import wvlet.uni.cli.launcher.*

class GitCli(@option(prefix = "-v,--verbose", description = "Verbose") verbose: Boolean = false):
  @command(description = "Initialize a repository")
  def init(@argument(name = "dir", description = "Directory") dir: String = "."): String =
    s"init ${dir}"

  @command(description = "Clone a repository")
  def clone(
      @argument(name = "url", description = "Repository URL") url: String,
      @option(prefix = "--depth", description = "Clone depth") depth: Option[Int] = None
  ): String = s"clone ${url} depth=${depth.getOrElse(0)}"

@main def git(args: String*): Unit =
  val result = Launcher.of[GitCli].execute(args)
  result.executedMethod.foreach { (method, value) => println(s"${method.name} -> ${value}") }
```

`executedMethod` holds the `MethodSurface` and the return value.
`@command(isDefault = true)` marks the method run when no sub-command is given;
without one, running with no arguments prints help
(`Launcher.of[A].withShowHelpOnNoArgs(false)` turns that off).
`Launcher.of[Main].addModule[Sub]("name", "description")` nests another
command class.

## Colors and progress

```scala
import wvlet.uni.cli.{ProgressBar, Spinner, Tint}

println(Tint.green("ok"))
println(Tint.red.bold("error"))
println(Tint.hex("#ff8800").bgBlack("custom"))
println(Tint.yellow.noColor("plain"))     // disable styling (e.g. when not a TTY)

val bar = ProgressBar(total = 3).withPrefix("Copying ").withShowCount(true).start()
(1 to 3).foreach(_ => bar.increment())
bar.finish()                               // or bar.fail()

val spinner = Spinner().withText("Loading...").start()
spinner.succeed("Loaded")                  // also fail, warn, info, stop
```

Progress bars and spinners write to `System.err`; use `.withStream(out)` to
redirect and `.withEnabled(false)` in non-interactive runs.

## Terminal emulator

`TerminalEmulator` is a pure-Scala xterm-compatible emulator (JVM, Scala.js,
Native). Feed it a program's output and read the screen, so tests can check
what a TUI draws instead of raw escape bytes:

```scala
import wvlet.uni.cli.term.{TerminalConfig, TerminalEmulator, TerminalLink}

val term = TerminalEmulator(TerminalConfig(cols = 20, rows = 3))
term.write("\u001b[1mhello\u001b[0m\r\nworld")
assert(term.screen.text == "hello\nworld")
assert(term.screen.lineText(0) == "hello")

// Keys typed into the terminal, encoded for the program
term.onData(bytes => ())                   // connect to a PTY's input
term.sendKey("ArrowUp")                    // W3C KeyboardEvent.key names

// Shell integration (OSC 133): the shell marks prompts, commands, and exit codes
term.write("\u001b]133;A\u0007$ \u001b]133;B\u0007false\r\n\u001b]133;C\u0007\u001b]133;D;1\u0007")
assert(term.commands.map(c => (c.command, c.exitCode)) == Seq(("false", Some(1))))
term.onCommandFinished(c => if c.isFailed then println(c.output))

// OSC 8 links come from program output: open only safe schemes
assert(TerminalLink.isAllowed("https://wvlet.org/uni"))
assert(!TerminalLink.isAllowed("javascript:alert(1)"))
```

In a Scala.js UI, `wvlet.uni.dom.TerminalView` draws the emulator in the DOM.
It uses `TerminalTheme.uniDark` by default (presets: `uniLight`, `solarizedDark`,
`nord`, `dracula`, and more in `TerminalTheme.presets`), switches with
`setTheme`, and opens http/https/mailto links on Cmd+click (Ctrl elsewhere).
OSC 133 prompts get a green or red marker, and Cmd+Up/Down (Ctrl+Shift+Up/Down
elsewhere) or `view.scrollToPreviousPrompt()` / `scrollToNextPrompt()` jump between them:

```scala skip
import wvlet.uni.cli.term.{PtyProcess, TerminalEmulator}
import wvlet.uni.dom.{TerminalTheme, TerminalView, TerminalViewConfig}

val term = TerminalEmulator()
val view = TerminalView(term, TerminalViewConfig().withFontSize(13))
view.onResize((cols, rows) => pty.resize(cols, rows))
view.setTheme(TerminalTheme.uniLight)      // e.g. follow the app's light mode
view.onLinkClick(uri => openInBrowser(uri)) // Electron: shell.openExternal in main
PtyProcess.connect(term, pty)               // pty: NodePty.spawn(nodePty, shell, ...)
div(styleAttr -> "height: 400px", view)
```

The PTY needs the native `node-pty` addon, which the app installs and passes to
`NodePty.spawn`; see the uni-harness example's Terminal tab.

On the JVM (Java 22+) and Scala Native, `PosixPty.spawn` runs a program in a
real PTY on Linux and macOS, with no extra dependency. The program gets the PTY
as its controlling terminal, so `stty size`, Ctrl-C, and SIGWINCH on `resize`
work. On Windows and older JVMs it throws `UnsupportedOperationException`, so
check `PosixPty.isSupported` (or `unsupportedReason`):

```scala
import wvlet.uni.cli.term.{PosixPty, PtyOptions, PtyProcess}

def runShell(): Unit =
  if PosixPty.isSupported then
    val terminal = TerminalEmulator(TerminalConfig(cols = 80, rows = 24))
    val shell    = PosixPty.spawn("bash", Seq("-l"), PtyOptions().withSize(80, 24))
    PtyProcess.connect(terminal, shell) // connect before output can be missed
    shell.onExit(code => println(s"exit ${code}")) // 128 + signal if killed
    shell.resize(100, 30)                          // the program gets SIGWINCH
    shell.kill()                                   // SIGHUP, like closing a window
```
