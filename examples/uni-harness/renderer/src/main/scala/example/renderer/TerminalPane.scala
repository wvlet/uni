package example.renderer

import org.scalajs.dom
import wvlet.uni.cli.term.{TerminalConfig, TerminalEmulator}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.{MediaQuery, TerminalTheme, TerminalView, TerminalViewConfig}
import wvlet.uni.rx.{Cancelable, Rx}

import scala.language.implicitConversions
import scala.scalajs.js

/**
  * The dock's Terminal tab: a [[TerminalView]] of a [[TerminalEmulator]]. In Electron it runs the
  * user's shell in the main process through the preload script's `uniPty` bridge; in a browser,
  * where no shell is available, it runs [[DemoShell]]. It uses Uni's terminal theme in the app's
  * light or dark mode.
  */
class TerminalPane(appTheme: Rx[Theme]) extends RxElement:
  private val term = TerminalEmulator(TerminalConfig().withScrollbackLines(5000))
  private val view = TerminalView(term, TerminalViewConfig().withFontSize(12.5))

  private def preloadBridge(name: String): Option[js.Dynamic] =
    val b = dom.window.asInstanceOf[js.Dynamic].selectDynamic(name)
    if js.isUndefined(b) || b == null then
      None
    else
      Some(b)

  private val bridge: Option[js.Dynamic] = preloadBridge("uniPty")

  private var started                = false
  private var themeWatch: Cancelable = Cancelable.empty

  // In Electron, the main process opens links in the system browser. In a browser the view's
  // default, window.open, opens a new tab.
  preloadBridge("uniShell").foreach { shell =>
    view.onLinkClick(uri => shell.openExternal(uri))
  }

  view.onResize { (cols, rows) =>
    if started then
      bridge.foreach(_.resize(cols, rows))
  }

  private def start(): Unit =
    if !started then
      started = true
      bridge match
        case Some(pty) =>
          pty.onData((data: String) => term.write(data))
          pty.onExit((code: Int) =>
            term.write(s"\r\n\u001b[2m[process exited with code ${code}]\u001b[0m\r\n")
          )
          term.onData(data => pty.write(data))
          pty.start(term.cols, term.rows)
        case None =>
          DemoShell(term).start()

  override def onMount(node: Any): Unit =
    themeWatch = appTheme
      .join(MediaQuery.prefersDarkMode)
      .run { (theme, systemDark) =>
        val dark =
          theme match
            case Theme.System =>
              systemDark
            case Theme.Light =>
              false
            case Theme.Dark =>
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

  override def render: RxElement = div(
    cls -> "h-full overflow-hidden rounded-xl border border-line",
    view
  )

end TerminalPane

/**
  * A tiny line-based shell that runs in the browser, so the web build's Terminal tab shows the
  * emulator without a real shell: colors, wide characters, line editing, and the alternate screen.
  */
class DemoShell(term: TerminalEmulator):
  private val line = StringBuilder()

  private def out(s: String): Unit = term.write(s)

  private def prompt(): Unit = out("\u001b[1;36muni\u001b[0m \u001b[2m$\u001b[0m ")

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
      run(line.toString.trim)
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

  private def run(command: String): Unit =
    command match
      case "" =>
        ()
      case "help" =>
        out(
          "Commands: \u001b[33mcolors\u001b[0m, \u001b[33mlinks\u001b[0m, \u001b[33mwide\u001b[0m, \u001b[33mclear\u001b[0m, \u001b[33mecho\u001b[0m <text>\r\n"
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
      case "clear" =>
        out("\u001b[H\u001b[2J")
      case c if c.startsWith("echo ") =>
        out(c.stripPrefix("echo ") + "\r\n")
      case other =>
        out(s"\u001b[31m${other}: command not found\u001b[0m\r\n")

end DemoShell
