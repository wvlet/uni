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

import wvlet.uni.log.LogSupport

import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets

/**
  * Raw mode and the window size of the process's terminal (stdin and stdout), which
  * [[JvmTerminalIO]] uses.
  */
private[cli] trait NativeTerminal:
  /**
    * True when both stdin and stdout are terminals and raw mode can be used
    */
  def isTTY: Boolean

  /**
    * Save the current mode and switch to raw mode. Returns false on failure, leaving the terminal
    * as it was.
    */
  def enterRawMode(): Boolean

  /**
    * Restore the mode saved by [[enterRawMode]]
    */
  def restore(): Unit

  def windowSize: Option[TerminalSize]

private[cli] object NativeTerminal extends LogSupport:
  enum Backend:
    case Ffm
    case Stty

  /**
    * The back-ends to try, in order. FFM needs Java 22; `stty` is the fallback on Unix.
    */
  def backends(javaVersion: Int, isWindows: Boolean): List[Backend] =
    if isWindows then
      Nil
    else if javaVersion >= 22 then
      List(Backend.Ffm, Backend.Stty)
    else
      List(Backend.Stty)

  /**
    * The first back-end that loads and finds a terminal, or None for line mode
    */
  def load(javaVersion: Int, isWindows: Boolean): Option[NativeTerminal] = backends(
    javaVersion,
    isWindows
  ).iterator
    .flatMap { backend =>
      try
        val t: NativeTerminal =
          backend match
            case Backend.Ffm =>
              PosixTerminal()
            case Backend.Stty =>
              SttyTerminal()
        Some(t).filter(_.isTTY)
      catch
        case e: Throwable =>
          debug(s"${backend} terminal access is unavailable: ${e}")
          None
    }
    .nextOption()

end NativeTerminal

/**
  * Terminal control through the `stty` command, for JVMs without the Foreign Function & Memory API
  * (Java 17–21). Each call runs `stty` with stdin redirected from `/dev/tty`, as JLine's
  * ExecTerminalProvider does. `stty raw` clears the same flags as `cfmakeraw`, including ISIG, so
  * Ctrl-C arrives as a byte as it does with [[PosixTerminal]].
  *
  * The window size is cached, since the line editor reads it on every redraw. It is refreshed on
  * SIGWINCH, or on each [[enterRawMode]] when SIGWINCH is unavailable.
  */
private[cli] class SttyTerminal(tty: File = File("/dev/tty"))
    extends NativeTerminal
    with LogSupport:
  @volatile
  private var saved: Option[String] = None

  @volatile
  private var cachedSize: Option[TerminalSize] = None

  // A successful `stty -g` proves that stty exists and /dev/tty is our controlling terminal
  override val isTTY: Boolean =
    SttyTerminal.consoleIsTerminal && tty.canRead && stty("-g").isDefined

  // When SIGWINCH keeps the size current, enterRawMode need not read it again
  private val watchesResize: Boolean = isTTY && watchResize()

  if isTTY then
    cachedSize = readSize()

  override def enterRawMode(): Boolean =
    stty("-g") match
      case Some(mode) =>
        saved = Some(mode)
        if !watchesResize then
          cachedSize = readSize()
        stty("raw", "-echo").isDefined
      case None =>
        false

  /**
    * Refresh the size on SIGWINCH, still calling a handler installed before ours
    */
  private def watchResize(): Boolean =
    try
      val winch                                   = sun.misc.Signal("WINCH")
      var previous: sun.misc.SignalHandler | Null = null
      previous = sun
        .misc
        .Signal
        .handle(
          winch,
          signal =>
            cachedSize = readSize()
            val p = previous
            if p != null && p != sun.misc.SignalHandler.SIG_DFL &&
              p != sun.misc.SignalHandler.SIG_IGN
            then
              p.handle(signal)
        )
      true
    catch
      case e: IllegalArgumentException =>
        debug(s"SIGWINCH is unavailable: ${e.getMessage}")
        false

  override def restore(): Unit = saved.foreach(mode => stty(mode))

  override def windowSize: Option[TerminalSize] = cachedSize

  private def readSize(): Option[TerminalSize] = stty("size").flatMap(SttyTerminal.parseSize)

  /**
    * Run stty on the terminal. Returns its trimmed stdout, or None if it fails to start or exits
    * with a non-zero status.
    */
  private def stty(args: String*): Option[String] =
    try
      val process = ProcessBuilder(("stty" +: args)*)
        .redirectInput(Redirect.from(tty))
        .redirectError(Redirect.DISCARD)
        .start()
      val out = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if process.waitFor() == 0 then
        Some(out.trim)
      else
        None
    catch
      case e: Exception =>
        debug(s"stty ${args.mkString(" ")} failed: ${e}")
        None

end SttyTerminal

private[cli] object SttyTerminal:
  /**
    * Parse the output of `stty size`, which prints `rows columns`
    */
  def parseSize(s: String): Option[TerminalSize] =
    s.trim.split("\\s+") match
      case Array(rows, cols) =>
        (rows.toIntOption, cols.toIntOption) match
          case (Some(r), Some(c)) if r > 0 && c > 0 =>
            Some(TerminalSize(c, r))
          case _ =>
            None
      case _ =>
        None

  /**
    * Whether stdin and stdout are both terminals. Before Java 22, `System.console()` is non-null
    * only then. Java 22+ always returns a console, so ask `Console.isTerminal()` there.
    */
  def consoleIsTerminal: Boolean =
    val console = System.console()
    console != null && {
      try
        classOf[java.io.Console].getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
      catch
        case _: ReflectiveOperationException =>
          true
    }

end SttyTerminal
