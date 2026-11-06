package example.renderer

import org.scalajs.dom
import wvlet.uni.cli.term.{TerminalCommand, TerminalConfig, TerminalEmulator}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.{MediaQuery, TerminalTheme, TerminalView, TerminalViewConfig}
import wvlet.uni.rx.{Cancelable, Rx}
import wvlet.uni.ui.ColorScheme

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * The dock's Terminal tab: a [[TerminalView]] of a [[TerminalEmulator]]. In Electron it runs the
  * user's shell in the main process through the preload script's `uniPty` bridge; in a browser,
  * where no shell is available, it runs [[DemoShell]]. It uses Uni's terminal theme in the app's
  * light or dark mode.
  *
  * A header shows the title the program set, and a Restart button once the shell exited. A bell
  * flashes the pane's border and calls `onBell`, so the dock can mark the tab while it is hidden.
  *
  * The shell marks each command (OSC 133; the main process starts zsh and bash with
  * `ShellIntegration`). After a command fails, the header offers "Explain error", which passes a
  * prompt with the command and its output to `onAsk`, for the chat.
  */
class TerminalPane(
    appTheme: Rx[ColorScheme],
    onBell: () => Unit = () => (),
    onAsk: String => Unit = _ => ()
) extends RxElement:
  private[renderer] val term = TerminalEmulator(TerminalConfig().withScrollbackLines(5000))
  private val view           = TerminalView(term, TerminalViewConfig().withFontSize(12.5))

  private def preloadBridge(name: String): Option[js.Dynamic] =
    val b = dom.window.asInstanceOf[js.Dynamic].selectDynamic(name)
    if js.isUndefined(b) || b == null then
      None
    else
      Some(b)

  private val bridge: Option[js.Dynamic] = preloadBridge("uniPty")

  private var started                = false
  private var themeWatch: Cancelable = Cancelable.empty

  private val title    = Rx.variable("")
  private val exited   = Rx.variable(false)
  private val flashing = Rx.variable(false)
  // The last command, when it failed
  private[renderer] val failed = Rx.variable(Option.empty[TerminalCommand])

  // The pane unmounts whenever the dock shows another tab, but the emulator and shell live on.
  // These listeners share the emulator's lifetime, so a bell on a hidden tab still reaches onBell.
  term.onTitleChange(t => title := t)
  term.onBell { () =>
    if !flashing.get then
      flashing := true
      dom.window.setTimeout(() => flashing := false, 180)
    onBell()
  }

  term.onCommandFinished { c =>
    failed := (
      if c.isFailed then
        Some(c)
      else
        None
    )
  }

  private[renderer] def explain(): Unit = failed
    .get
    .foreach { c =>
      failed := None
      onAsk(TerminalPane.explainPrompt(c))
    }

  // In Electron, the main process opens links in the system browser. In a browser the view's
  // default, window.open, opens a new tab.
  preloadBridge("uniShell").foreach { shell =>
    view.onLinkClick(uri => shell.openExternal(uri))
  }

  view.onResize { (cols, rows) =>
    if started && !exited.get then
      bridge.foreach(_.resize(cols, rows))
  }

  private def start(): Unit =
    if !started then
      started = true
      bridge match
        case Some(pty) =>
          // The preload bridge cannot remove these listeners, so they are registered once and a
          // restart only sends another pty-start
          pty.onData((data: String) => term.write(data))
          pty.onExit((code: Int) =>
            exited := true
            // The title described the program that just ended
            title := ""
            // Leave the alternate screen and reset modes a killed full-screen program left on
            term.write(
              s"\u001b[?1049l\u001b[!p\r\n\u001b[2m[process exited with code ${code}] Press Enter to restart\u001b[0m\r\n"
            )
          )
          term.onData { data =>
            if !exited.get then
              pty.write(data)
            else if data.contains('\r') then
              restart()
          }
          pty.start(term.cols, term.rows)
        case None =>
          DemoShell(term).start()

  /** Start a new shell after the previous one exited, keeping the scrollback. */
  private[renderer] def restart(): Unit = bridge.foreach { pty =>
    if exited.get then
      exited := false
      pty.start(term.cols, term.rows)
      view.focus()
  }

  override def onMount(node: Any): Unit =
    themeWatch = appTheme
      .join(MediaQuery.prefersDarkMode)
      .run { (theme, systemDark) =>
        val dark =
          theme match
            case ColorScheme.System =>
              systemDark
            case ColorScheme.Light =>
              false
            case ColorScheme.Dark =>
              true
        view.setTheme(
          if dark then
            TerminalTheme.uniDark
          else
            TerminalTheme.uniLight
        )
      }
    // Start after the view measured the pane, so the shell starts at the right size
    dom.window.setTimeout(() => start(), 0)
    dom.window.setTimeout(() => view.focus(), 0)

  override def beforeUnmount: Unit = themeWatch.cancel

  private def header: RxElement = div(
    cls -> "flex h-7 shrink-0 items-center gap-2 px-1",
    span(
      cls         -> "min-w-0 flex-1 truncate font-mono text-[11px] text-muted",
      data("tip") -> title.map(TerminalPane.displayTitle),
      title.map(TerminalPane.displayTitle)
    ),
    failed.map {
      case Some(c) =>
        button(
          cls ->
            "rounded-md border border-line px-2 py-0.5 text-[11px] font-medium text-fg transition hover:bg-hover active:scale-95",
          data("tip") -> s"Ask the chat why this command exited with ${c.exitCode.getOrElse(0)}",
          onclick     -> { () =>
            explain()
          },
          "Explain error"
        )
      case None =>
        span()
    },
    exited.map { isExited =>
      if isExited then
        button(
          cls ->
            "rounded-md border border-line px-2 py-0.5 text-[11px] font-medium text-fg transition hover:bg-hover active:scale-95",
          onclick -> { () =>
            restart()
          },
          "Restart"
        )
      else
        span()
    }
  )

  override def render: RxElement = div(
    cls -> "flex h-full flex-col",
    header,
    div(
      cls ->
        flashing.map { on =>
          val ring =
            if on then
              "border-glow ring-2 ring-glow/40"
            else
              "border-line ring-0"
          s"min-h-0 flex-1 overflow-hidden rounded-xl border transition-[border-color,box-shadow] duration-150 ${ring}"
        },
      view
    )
  )

end TerminalPane

object TerminalPane:
  val fallbackTitle = "Terminal"

  // Output beyond this is cut from the top, keeping the end, where errors usually are
  private val maxOutputLines = 80
  private val maxOutputChars = 6000

  /** The chat prompt that asks why a command failed, with the end of its output. */
  def explainPrompt(c: TerminalCommand): String =
    val lines = c.output.split("\n", -1).toSeq
    val tail  = lines.takeRight(maxOutputLines).mkString("\n").takeRight(maxOutputChars)
    val cut   =
      if tail.length < c.output.length then
        "(earlier output cut)\n"
      else
        ""
    val command =
      if c.command.isEmpty then
        "A command"
      else
        s"The command `${c.command}`"
    val output =
      if tail.isEmpty then
        "It printed nothing."
      else
        s"Its output:\n\n```\n${cut}${tail}\n```"
    s"${command} failed with exit code ${c.exitCode.getOrElse(0)} in my terminal. " +
      s"What went wrong, and how do I fix it?\n\n${output}"

  /** The title to show: whitespace collapsed, at most 120 characters, or "Terminal" when unset. */
  def displayTitle(raw: String): String =
    val t = raw.trim.replaceAll("\\s+", " ")
    if t.isEmpty then
      fallbackTitle
    else if t.length > 120 then
      t.take(119) + "…"
    else
      t

end TerminalPane

/**
  * A tiny line-based shell that runs in the browser, so the web build's Terminal tab shows the
  * emulator without a real shell: colors, wide characters, line editing, and the alternate screen.
  */
class DemoShell(term: TerminalEmulator):
  private val line = StringBuilder()

  private def out(s: String): Unit = term.write(s)

  // With OSC 133 marks, as the zsh and bash integration emits them
  private def prompt(): Unit = out(
    "\u001b]133;A\u0007\u001b[1;36muni\u001b[0m \u001b[2m$\u001b[0m \u001b]133;B\u0007"
  )

  def start(): Unit =
    out("\u001b[1mUni terminal\u001b[0m: a pure-Scala xterm-compatible emulator.\r\n")
    out(
      "No shell runs in the browser; this demo shell echoes what you type. Try \u001b[33mhelp\u001b[0m.\r\n\r\n"
    )
    prompt()
    term.onData(onInput)

  private def onInput(data: String): Unit = data.foreach {
    case '\r' =>
      out("\r\n")
      val command = line.toString.trim
      if command.nonEmpty then
        out("\u001b]133;C\u0007")
        val code = run(command)
        out(s"\u001b]133;D;${code}\u0007")
      line.clear()
      prompt()
    case '\u007f' =>
      if line.nonEmpty then
        line.deleteCharAt(line.length - 1)
        out("\b \b")
    case '\u0003' =>
      out("^C\r\n")
      line.clear()
      prompt()
    case c if c >= ' ' =>
      line.append(c)
      out(c.toString)
    case _ =>
      ()
  }

  // Run a command and return its exit code
  private def run(command: String): Int =
    var status = 0
    command match
      case "" =>
        ()
      case "help" =>
        out(
          "Commands: \u001b[33mcolors\u001b[0m, \u001b[33mlinks\u001b[0m, \u001b[33mwide\u001b[0m, \u001b[33mbell\u001b[0m, \u001b[33mtitle\u001b[0m <text>, \u001b[33mclear\u001b[0m, \u001b[33mecho\u001b[0m <text>, \u001b[33mfalse\u001b[0m\r\n"
        )
      case "colors" =>
        for i <- 0 until 16 do
          out(s"\u001b[48;5;${i}m  ")
        out("\u001b[0m\r\n")
        for i <- 0 until 36 do
          out(s"\u001b[48;5;${16 + i * 6}m ")
        out("\u001b[0m\r\n")
        for i <- 0 until 48 do
          val r = 255 - i * 5
          out(s"\u001b[48;2;${r};${i * 5};160m ")
        out("\u001b[0m\r\n")
        out(
          "\u001b[1mbold\u001b[0m \u001b[3mitalic\u001b[0m \u001b[4munderline\u001b[0m \u001b[9mstrike\u001b[0m \u001b[7minverse\u001b[0m\r\n"
        )
      case "links" =>
        // OSC 8 hyperlinks; only http, https, and mailto open
        def link(uri: String, text: String) = s"\u001b]8;;${uri}\u001b\\${text}\u001b]8;;\u001b\\"
        out(
          s"${link("https://wvlet.org/uni/", "Uni docs")}  ${link(
              "mailto:dev@example.com",
              "mail"
            )}  "
        )
        out(s"${link("javascript:alert(1)", "not a link")}\r\n")
      case "wide" =>
        out("日本語のテキスト | emoji 🎉 | combining é\r\n")
      case "bell" =>
        out("\u0007")
      case c if c.startsWith("title ") =>
        // OSC 2 sets the window title
        out(s"\u001b]2;${c.stripPrefix("title ")}\u0007")
      case "clear" =>
        out("\u001b[H\u001b[2J")
      case c if c.startsWith("echo ") =>
        out(c.stripPrefix("echo ") + "\r\n")
      case "false" =>
        status = 1
      case other =>
        out(s"\u001b[31m${other}: command not found\u001b[0m\r\n")
        status = 127
    end match
    status

  end run

end DemoShell
