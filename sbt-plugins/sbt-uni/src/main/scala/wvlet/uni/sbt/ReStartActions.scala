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
import sbt.util.Logger
import sbt.internal.util.complete.DefaultParsers.*
import sbt.internal.util.complete.Parser

import java.io.File
import scala.sys.process.Process

/**
  * The forking actions behind the `reStart`/`reStop`/`reStatus` tasks. Kept free of sbt task
  * plumbing so they are easy to read and test; [[UniPlugin]] wires sbt keys to these functions.
  */
object ReStartActions:

  /**
    * Extra command-line arguments parsed from the `reStart` input: app args and (after `---`) JVM
    * args.
    */
  final case class ExtraArgs(jvmArgs: Seq[String], appArgs: Seq[String])

  /**
    * Parser for `reStart <appArg>* [--- <jvmArg>*]`. Everything before a literal `---` token is
    * passed to the application; everything after it becomes extra JVM options for the forked JVM.
    */
  val startArgsParser: Parser[ExtraArgs] = spaceDelimited("<args> [--- <jvm-args>]").map { args =>
    args.indexOf("---") match
      case -1 =>
        ExtraArgs(jvmArgs = Nil, appArgs = args)
      case idx =>
        ExtraArgs(jvmArgs = args.drop(idx + 1), appArgs = args.take(idx))
  }

  def restartApp(
      log: Logger,
      ansi: Boolean,
      logTag: String,
      project: ProjectRef,
      options: ForkOptions,
      mainClass: Option[String],
      classpath: Seq[File],
      defaultArgs: Seq[String],
      colors: Seq[String],
      extra: ExtraArgs
  ): Unit =
    stopApp(log, ansi, project)
    startApp(log, ansi, logTag, project, options, mainClass, classpath, defaultArgs, colors, extra)

  def startApp(
      log: Logger,
      ansi: Boolean,
      logTag: String,
      project: ProjectRef,
      options: ForkOptions,
      mainClass: Option[String],
      classpath: Seq[File],
      defaultArgs: Seq[String],
      colors: Seq[String],
      extra: ExtraArgs
  ): Unit =
    val theMainClass = mainClass.getOrElse(
      sys.error("No main class detected. Set `uniRestart / mainClass := Some(\"...\")`.")
    )
    // Seed the color pool from the configured names on first use, then hand out one color.
    val color  = GlobalState.updateAndGet(_.ensureColors(colors).takeColor)
    val logger = SysoutLogger(logTag, color, ansi)
    ReStartColors
      .colorLogger(log, ansi)
      .info(
        s"[YELLOW]Starting application ${formatAppName(
            project.project,
            color
          )} in the background ..."
      )

    val appProcess =
      AppProcess(project, color, logger) {
        forkRun(
          options,
          theMainClass,
          classpath,
          defaultArgs ++ extra.appArgs,
          logger,
          extra.jvmArgs
        )
      }
    registerAppProcess(project, appProcess)

  end startApp

  def stopApp(log: Logger, ansi: Boolean, project: ProjectRef): Unit =
    val clog = ReStartColors.colorLogger(log, ansi)
    GlobalState.get().getProcess(project) match
      case Some(p) =>
        if p.isRunning then
          clog.info(s"[YELLOW]Stopping application ${formatApp(p)} (by killing the forked JVM) ...")
          p.stop()
      case None =>
        clog.info(
          s"[YELLOW]Application ${formatAppName(project.project, "[BOLD]")} not yet started"
        )
    GlobalState.update(_.removeProcessAndColor(project))

  def stopApps(log: Logger, ansi: Boolean): Unit = GlobalState
    .get()
    .runningProjects
    .foreach(stopApp(log, ansi, _))

  def showStatus(log: Logger, ansi: Boolean, project: ProjectRef): Unit =
    val clog = ReStartColors.colorLogger(log, ansi)
    GlobalState.get().getProcess(project).find(_.isRunning) match
      case Some(p) =>
        clog.info(s"[GREEN]Application ${formatApp(p, "[GREEN]")} is currently running")
      case None =>
        clog.info(
          s"[YELLOW]Application ${formatAppName(
              project.project,
              "[BOLD]"
            )} is currently NOT running"
        )

  private def registerAppProcess(project: ProjectRef, process: AppProcess): Unit = GlobalState
    .update { state =>
      // Defensively stop any still-running process before overwriting its slot, so we never leak a
      // forked JVM if two starts raced.
      val old = state.getProcess(project)
      if old.exists(_.isRunning) then
        old.get.stop()
      state.addProcess(project, process)
    }

  private def forkRun(
      options: ForkOptions,
      mainClass: String,
      classpath: Seq[File],
      args: Seq[String],
      log: Logger,
      extraJvmArgs: Seq[String]
  ): Process =
    log.info(args.mkString(s"Starting ${mainClass}.main(", ", ", ")"))
    val cpString     = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val javaArgs     = "-classpath" :: cpString :: mainClass :: args.toList
    val finalOptions = options
      .withOutputStrategy(options.outputStrategy.getOrElse(OutputStrategy.LoggedOutput(log)))
      .withRunJVMOptions(options.runJVMOptions ++ extraJvmArgs)
    Fork.java.fork(finalOptions, javaArgs)

  private def formatAppName(
      projectName: String,
      projectColor: String,
      color: String = "[YELLOW]"
  ): String = s"[RESET]${projectColor}${projectName}[RESET]${color}"

  private def formatApp(p: AppProcess, color: String = "[YELLOW]"): String = formatAppName(
    p.projectName,
    p.consoleColor,
    color
  )

end ReStartActions
