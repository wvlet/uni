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
import sbt.Keys.*
import sbt.internal.util.ConsoleAppender
import xsbti.FileConverter
import wvlet.uni.http.codegen.{HttpCodeGenerator, ServiceScanner}
import scala.annotation.nowarn

/**
  * sbt 2.x plugin for generating HTTP/RPC client code from Scala 3 traits.
  *
  * Unlike sbt-airframe (which forks a JVM for code generation), this plugin runs the codegen
  * in-process. This is possible because sbt 2.x uses Scala 3 for the metabuild, so the plugin can
  * directly call uni's codegen as a library.
  *
  * It also provides a `uniRestart` task (ported from sbt-revolver's `reStart`, which has not been
  * released for sbt 2.x) to fork-run an application in the background. Combine it with sbt's file
  * watch (`~uniRestart`) to relaunch the process on every code change. See [[ReStartActions]].
  *
  * Usage in build.sbt:
  * {{{
  * lazy val app = project
  *   .enablePlugins(UniPlugin)
  *   .settings(
  *     uniHttpClients := Seq("com.example.api.UserService:rpc")
  *   )
  *   .dependsOn(api) // project containing the service trait
  *   .settings(
  *     // optional: start the forked process from a directory other than the project root
  *     uniRestart / baseDirectory := baseDirectory.value / "server"
  *   )
  *
  * // run the app in a forked JVM and relaunch it whenever sources change:
  * //   sbt> ~app/uniRestart arg1 arg2 --- -Xmx512m
  * //   sbt> app/uniStop
  * //   sbt> app/uniStatus
  * }}}
  */
object UniPlugin extends AutoPlugin:

  object autoImport:
    val uniHttpClients = settingKey[Seq[String]](
      "Client generation targets. Format: 'fqcn:clientType[:targetPackage]'"
    )

    @transient
    val uniHttpGenerateClient = taskKey[Seq[File]]("Generate HTTP client code from service traits")

    val uniHttpCodegenOutDir = settingKey[File]("Output directory for generated code")

    // --- uniRestart (background fork-run), ported from sbt-revolver's reStart ---

    val uniRestart = inputKey[Unit](
      "(Re)start the application in a forked JVM in the background. Stops the previous run first, " +
        "so `~uniRestart` relaunches it on every code change. " +
        "Usage: uniRestart <app-args>* [--- <jvm-args>*]"
    )

    val uniStop   = taskKey[Unit]("Stop the application started by uniRestart")
    val uniStatus = taskKey[Unit]("Show whether the uniRestart application is currently running")

    val uniRestartArgs = settingKey[Seq[String]](
      "Default arguments passed to the application on uniRestart"
    )

    @transient
    val uniRestartForkOptions = taskKey[ForkOptions](
      "Fork options (java home, JVM opts, env) used by uniRestart"
    )

    val uniRestartLogTag = settingKey[String](
      "Prefix used to tag the forked application's console output"
    )

    val uniRestartColors = settingKey[Seq[String]](
      "Console color names assigned to background apps to distinguish output"
    )

  end autoImport

  import autoImport.*

  override def requires: Plugins = sbt.plugins.JvmPlugin
  override def trigger           = noTrigger

  override def projectSettings: Seq[Setting[?]] =
    Seq(
      uniHttpClients        := Seq.empty,
      uniHttpCodegenOutDir  := (Compile / sourceManaged).value,
      uniHttpGenerateClient := {
        given FileConverter = fileConverter.value
        val log             = streams.value.log
        val outDir          = uniHttpCodegenOutDir.value
        val clients         = uniHttpClients.value

        if clients.isEmpty then
          log.debug("uniHttpClients is empty, skipping code generation")
          Seq.empty
        else
          // Compile dependent projects first to produce .class files
          val _ = (Compile / compile).all(dependentProjects).value

          // Build a classloader from dependent project class dirs + dependency JARs
          val depClassDirs: Seq[java.io.File] = (Compile / classDirectory)
            .all(dependentProjects)
            .value
          val depClasspath: Seq[java.nio.file.Path] = (Compile / dependencyClasspath).value.files
          val allUrls = (depClassDirs.map(_.toURI.toURL) ++ depClasspath.map(_.toUri.toURL)).toArray
          val classLoader = java.net.URLClassLoader(allUrls, getClass.getClassLoader)

          val generated = clients.flatMap { spec =>
            val config    = HttpCodeGenerator.parseConfig(spec)
            val className = config.apiClassName
            log.info(s"Generating client for ${className}")
            val service = ServiceScanner.scan(className, classLoader)
            HttpCodeGenerator.generateAndWrite(service, config, outDir)
          }
          generated
      },
      // Hook into source generation so generated code is compiled with user code
      Compile / sourceGenerators += uniHttpGenerateClient
    ) ++ uniRestartSettings

  /** uniRestart/uniStop/uniStatus settings, ported from sbt-revolver's reStart. */
  private def uniRestartSettings: Seq[Setting[?]] = Seq(
    uniRestart / mainClass     := (Compile / run / mainClass).value,
    uniRestart / fullClasspath := Def.uncached((Runtime / fullClasspath).value),
    // Working directory of the forked JVM. Defaults to the project root; override when the app must
    // start from elsewhere, e.g. `uniRestart / baseDirectory := baseDirectory.value / "server"`.
    uniRestart / baseDirectory := baseDirectory.value,
    // javaOptions / envVars are left unscoped: `uniRestart / <key>` delegates to the project value,
    // while still letting users override them specifically for uniRestart.
    uniRestartArgs        := Seq.empty,
    uniRestartLogTag      := thisProjectRef.value.project,
    uniRestartColors      := ReStartColors.basicColorsAndUnderlined,
    uniRestartForkOptions := {
      // Touch the temp dir so it is created before the forked JVM may rely on it.
      val _ = taskTemporaryDirectory.value
      ForkOptions()
        .withJavaHome(javaHome.value)
        .withOutputStrategy(outputStrategy.value)
        .withWorkingDirectory((uniRestart / baseDirectory).value)
        .withRunJVMOptions((uniRestart / javaOptions).value.toVector)
        .withConnectInput(false)
        .withEnvVars((uniRestart / envVars).value)
    },
    uniRestart := {
      val extra = ReStartActions.startArgsParser.parsed
      // Ensure this project's classes are compiled before forking.
      val _               = (Compile / products).value
      given FileConverter = fileConverter.value
      val classpath       = (uniRestart / fullClasspath).value.files.map(_.toFile)
      ReStartActions.restartApp(
        log = streams.value.log,
        ansi = ansiEnabled,
        logTag = uniRestartLogTag.value,
        project = thisProjectRef.value,
        options = uniRestartForkOptions.value,
        mainClass = (uniRestart / mainClass).value,
        classpath = classpath,
        defaultArgs = uniRestartArgs.value,
        colors = uniRestartColors.value,
        extra = extra
      )
    },
    uniStop   := ReStartActions.stopApp(streams.value.log, ansiEnabled, thisProjectRef.value),
    uniStatus := ReStartActions.showStatus(streams.value.log, ansiEnabled, thisProjectRef.value)
  )

  // Stop any background apps when the build is reloaded, so we never leak a forked JVM.
  override def globalSettings: Seq[Setting[?]] = Seq(
    onUnload ~= { previous => (state: State) =>
      ReStartActions.stopApps(state.log, ansiEnabled)
      previous(state)
    }
  )

  // Whether ANSI color is enabled in the current environment. The public Terminal accessors are
  // `private[sbt]`, so we fall back to the (deprecated but accessible) env-level flag.
  @nowarn("cat=deprecation")
  private def ansiEnabled: Boolean = ConsoleAppender.formatEnabledInEnv

  private def dependentProjects: ScopeFilter = ScopeFilter(
    inDependencies(ThisProject, transitive = true, includeRoot = false)
  )

end UniPlugin
