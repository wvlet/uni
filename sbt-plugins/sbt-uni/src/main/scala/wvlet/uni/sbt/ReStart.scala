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
package wvlet.uni.sbt

import sbt.*
import sbt.util.{Level, Logger}
import sbt.internal.util.complete.DefaultParsers.*
import sbt.internal.util.complete.Parser

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.sys.process.Process

/**
  * Background-process support for `reStart`, ported from sbt-revolver's `reStart`/`reStop` commands
  * so that sbt 2.x users can fork-run an application without depending on io.spray:sbt-revolver
  * (which has not been published for sbt 2.x).
  *
  * The forked JVM is tracked in a process-global [[GlobalState]] (an `AtomicReference`) rather than
  * in sbt's `State`, so a running app survives task re-invocations and can be stopped later.
  */

/**
  * A handle to an application running in a forked JVM. Watches the process for termination and
  * installs a JVM shutdown hook so the child is killed if sbt itself exits.
  */
final case class AppProcess(projectRef: ProjectRef, consoleColor: String, log: Logger)(
    process: Process
):
  @volatile
  private var finishState: Option[Int] = None

  private val shutdownHook = Thread(() =>
    if isRunning then
      log.info("... killing ...")
      process.destroy()
  )

  private val watchThread =
    val t = Thread(() =>
      val code = process.exitValue()
      finishState = Some(code)
      log.info(s"... finished with exit code ${code}")
      unregisterShutdownHook()
      val _ = GlobalState.update(_.removeProcessAndColor(projectRef))
    )
    t.setDaemon(true)
    t.start()
    t

  registerShutdownHook()

  def projectName: String = projectRef.project
  def isRunning: Boolean  = finishState.isEmpty

  def stop(): Unit =
    unregisterShutdownHook()
    process.destroy()
    process.exitValue()

  private def registerShutdownHook(): Unit = java
    .lang
    .Runtime
    .getRuntime
    .addShutdownHook(shutdownHook)

  private def unregisterShutdownHook(): Unit =
    try
      java.lang.Runtime.getRuntime.removeShutdownHook(shutdownHook)
    catch
      case _: IllegalStateException =>
        () // JVM is already shutting down

end AppProcess

/**
  * Immutable snapshot of all running [[AppProcess]]es plus a pool of console colors handed out to
  * background apps so their interleaved output stays distinguishable.
  */
case class ReStartState(
    processes: Map[ProjectRef, AppProcess],
    colorPool: Queue[String],
    seeded: Boolean
):
  def addProcess(project: ProjectRef, process: AppProcess): ReStartState = copy(processes =
    processes + (project -> process)
  )

  private def removeProcess(project: ProjectRef): ReStartState = copy(processes =
    processes - project
  )

  def removeProcessAndColor(project: ProjectRef): ReStartState =
    getProcess(project) match
      case Some(p) =>
        removeProcess(project).offerColor(p.consoleColor)
      case None =>
        this

  def runningProjects: Seq[ProjectRef]                    = processes.keys.toSeq
  def getProcess(project: ProjectRef): Option[AppProcess] = processes.get(project)

  /** Seed the color pool once from the configured color names (formatted as `[NAME]` tags). */
  def ensureColors(colors: Seq[String]): ReStartState =
    if seeded then
      this
    else
      copy(colorPool = Queue(colors.map(c => s"[${c.toUpperCase}]")*), seeded = true)

  def takeColor: (ReStartState, String) =
    colorPool.dequeueOption match
      case Some((color, rest)) =>
        (copy(colorPool = rest), color)
      case None =>
        (this, "")

  def offerColor(color: String): ReStartState =
    if color.nonEmpty then
      copy(colorPool = colorPool.enqueue(color))
    else
      this

end ReStartState

object ReStartState:
  def initial: ReStartState = ReStartState(Map.empty, Queue.empty, seeded = false)

/**
  * Process-global, lock-free holder of [[ReStartState]]. Not a full STM, so callers must fold all
  * dependent reads/writes into a single `update`/`updateAndGet` to avoid lost updates.
  */
object GlobalState:
  private val state = AtomicReference(ReStartState.initial)

  @tailrec
  def update(f: ReStartState => ReStartState): ReStartState =
    val orig = state.get()
    val next = f(orig)
    if !state.compareAndSet(orig, next) then
      update(f)
    else
      next

  @tailrec
  def updateAndGet[T](f: ReStartState => (ReStartState, T)): T =
    val orig          = state.get()
    val (next, value) = f(orig)
    if !state.compareAndSet(orig, next) then
      updateAndGet(f)
    else
      value

  def get(): ReStartState = state.get()

/**
  * Minimal ANSI color support. Messages embed `[NAME]` tags (e.g. `[YELLOW]`, `[RESET]`) which are
  * either expanded to ANSI escape codes or stripped, depending on whether the terminal supports
  * color. Ported from sbt-revolver's Utilities/SysoutLogger.
  */
object ReStartColors:
  import scala.Console.*

  private val simple: Seq[(String, String)] = Seq(
    "RED"     -> RED,
    "GREEN"   -> GREEN,
    "YELLOW"  -> YELLOW,
    "BLUE"    -> BLUE,
    "MAGENTA" -> MAGENTA,
    "CYAN"    -> CYAN,
    "WHITE"   -> WHITE
  )

  private def underlined(c: (String, String)): (String, String) = (s"_${c._1}", c._2 + UNDERLINED)

  private val mapping: Seq[(String, String)] =
    (Seq("BOLD" -> BOLD, "RESET" -> RESET) ++ simple ++ simple.map(underlined)).map((name, code) =>
      (s"[${name}]", code)
    )

  def basicColors: Seq[String]              = Seq("BLUE", "MAGENTA", "CYAN", "YELLOW", "GREEN")
  def basicColorsAndUnderlined: Seq[String] = basicColors ++ basicColors.map("_" + _)

  def colorize(ansi: Boolean, message: String): String =
    mapping.foldLeft(message)((m, kv) =>
      m.replace(
        kv._1,
        if ansi then
          kv._2
        else
          ""
      )
    )

  /** Wrap a logger so that `[NAME]` color tags in messages are expanded/stripped on the way out. */
  def colorLogger(base: Logger, ansi: Boolean): Logger =
    new Logger:
      def trace(t: => Throwable): Unit                      = base.trace(t)
      def success(message: => String): Unit                 = base.success(message)
      def log(level: Level.Value, message: => String): Unit = base.log(
        level,
        colorize(ansi, message)
      )

end ReStartColors

/**
  * A logger that prints directly with `println`, used to tag the forked app's stdout/stderr with a
  * per-app colored prefix. Used where no task `streams` logger is available (inside the watch
  * thread / fork output strategy).
  */
class SysoutLogger(appName: String, color: String, ansi: Boolean) extends Logger:
  def trace(t: => Throwable): Unit = t.printStackTrace()

  def success(message: => String): Unit = println(
    ReStartColors.colorize(ansi, s"${color}${appName}[RESET] success: ") + message
  )

  def log(level: Level.Value, message: => String): Unit =
    val levelStr =
      level match
        case Level.Error =>
          "[ERROR]"
        case Level.Warn =>
          "[WARN]"
        case _ =>
          ""
    println(ReStartColors.colorize(ansi, s"${color}${appName}[RESET]${levelStr} ") + message)

end SysoutLogger
