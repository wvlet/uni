package example.server

import wvlet.uni.cli.term.{NodePty, PtyOptions, PtyProcess, TerminalLink}
import wvlet.uni.electron.ElectronRPCServerConfig
import wvlet.uni.log.LogSupport

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * Runs the user's shell in a pseudo-terminal for the renderer's Terminal tab, one shell per
  * window. Output streams to the window as `pty-data` messages; the preload script's `uniPty`
  * bridge sends `pty-start`, `pty-input`, and `pty-resize`.
  *
  * Only top-level frames of the app's windows may drive the shell (the same sender check as
  * `ElectronRPCServer`), because a shell can run any command.
  */
object TerminalBridge extends LogSupport:
  private val senderCheck = ElectronRPCServerConfig()
  private val sessions    = mutable.HashMap.empty[Int, PtyProcess]
  // Windows whose "destroyed" event already stops their shell
  private val watched = mutable.HashSet.empty[Int]

  @JSExportTopLevel("wireTerminal")
  def wireTerminal(ipcMain: js.Dynamic, nodePty: js.Dynamic): Unit =
    on(ipcMain, "pty-start") { (event, args) =>
      val sender = event.sender
      val id     = sender.id.asInstanceOf[Int]
      sessions.remove(id).foreach(_.kill())
      val options = PtyOptions(cols = intArg(args, 0, 80), rows = intArg(args, 1, 24))
      val pty     = NodePty.spawn(
        nodePty,
        defaultShell,
        Seq.empty,
        homeDir.fold(options)(options.withCwd)
      )
      sessions(id) = pty
      // A shell replaced by a newer pty-start (e.g. after a reload) must not reach the new page
      def isCurrent: Boolean = sessions.get(id).contains(pty)
      def canSend: Boolean   = isCurrent && !sender.isDestroyed().asInstanceOf[Boolean]
      pty.onData { data =>
        if canSend then
          sender.send("pty-data", data)
      }
      pty.onExit { code =>
        if canSend then
          sender.send("pty-exit", code)
        if isCurrent then
          sessions.remove(id)
      }
      if watched.add(id) then
        sender.once(
          "destroyed",
          () =>
            watched.remove(id)
            sessions.remove(id).foreach(_.kill())
        )
    }
    on(ipcMain, "pty-input") { (event, args) =>
      if js.typeOf(args(0)) == "string" then
        session(event).foreach(_.write(args(0).asInstanceOf[String]))
    }
    on(ipcMain, "pty-resize") { (event, args) =>
      session(event).foreach(_.resize(intArg(args, 0, 80), intArg(args, 1, 24)))
    }

  end wireTerminal

  /**
    * Open links from the Terminal tab (`open-external`, sent by the preload script's `uniShell`
    * bridge) in the system browser. The URI comes from program output, so only http, https, and
    * mailto links open, and only from the app's own frames.
    */
  @JSExportTopLevel("wireLinks")
  def wireLinks(ipcMain: js.Dynamic, shell: js.Dynamic): Unit =
    on(ipcMain, "open-external") { (_, args) =>
      if js.typeOf(args(0)) == "string" then
        val uri = args(0).asInstanceOf[String]
        if TerminalLink.isAllowed(uri) then
          // Fails when the OS has no handler for the scheme, e.g. no mail app for mailto
          val onError: js.Function1[js.Any, Unit] = e => warn(s"Could not open a link: ${e}")
          shell.openExternal(uri).`catch`(onError)
        else
          warn(s"Rejected a link with a scheme the app does not open: ${uri.take(64)}")
    }

  // Each message carries at most two arguments; JavaScript passes undefined for missing ones
  private def on(ipcMain: js.Dynamic, channel: String)(
      handler: (js.Dynamic, js.Array[js.Any]) => Unit
  ): Unit =
    val listener: js.Function3[js.Dynamic, js.Any, js.Any, Unit] =
      (event, a, b) =>
        if senderCheck.isAllowedSender(event) then
          handler(event, js.Array(a, b))
        else
          warn(s"Rejected ${channel} from a frame that may not use the terminal")
    ipcMain.on(channel, listener)

  private def session(event: js.Dynamic): Option[PtyProcess] = sessions.get(
    event.sender.id.asInstanceOf[Int]
  )

  private def intArg(args: js.Array[js.Any], i: Int, default: Int): Int =
    if i < args.length && js.typeOf(args(i)) == "number" then
      args(i).asInstanceOf[Double].toInt
    else
      default

  private def env(name: String): Option[String] =
    val v = js.Dynamic.global.process.env.selectDynamic(name)
    if js.isUndefined(v) || v == null then
      None
    else
      Some(v.toString)

  private def defaultShell: String =
    if js.Dynamic.global.process.platform.toString == "win32" then
      env("COMSPEC").getOrElse("powershell.exe")
    else
      env("SHELL").getOrElse("/bin/sh")

  private def homeDir: Option[String] = env("HOME").orElse(env("USERPROFILE"))

end TerminalBridge
