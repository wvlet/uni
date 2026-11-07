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

    // --- uniOpenAPI: OpenAPI document generation from @Endpoint routers ---
    val uniOpenAPIRouters = settingKey[Seq[String]](
      "Routers to document: Scala object members returning a wvlet.uni.http.router.Router, e.g. 'com.example.Api.router'"
    )

    val uniOpenAPITitle   = settingKey[String]("OpenAPI info.title (default: project name)")
    val uniOpenAPIVersion = settingKey[String]("OpenAPI info.version (default: project version)")
    val uniOpenAPISchemaPackagePrefix = settingKey[Option[String]](
      "A package prefix stripped from schema names, e.g. Some(\"com.example.api.\")"
    )

    val uniOpenAPIErrorResponses = settingKey[Seq[Int]](
      "Error statuses every operation lists (default: 400, 500), e.g. Seq(400, 500, 503)"
    )

    val uniOpenAPIFormat    = settingKey[String]("Output format: yaml (default) or json")
    val uniOpenAPITargetDir = settingKey[File]("Directory for openapi.yaml / openapi.json")

    @transient
    val uniOpenAPI = taskKey[Seq[File]](
      "Generate an OpenAPI document from uniOpenAPIRouters (no-op when empty); runs on package"
    )

    // --- uniUiCss: uni-ui's stylesheet, copied out of the uni-ui jar ---

    val uniUiCssFile = settingKey[File](
      "Where uniUiCss copies uni-ui.css (default: <project>/target/uni-ui.css)"
    )

    // Transient (uncached): the copy is a side effect, and must happen again if the file is deleted
    @transient
    val uniUiCss = taskKey[Seq[File]](
      "Copy uni-ui.css from the uni-ui dependency to uniUiCssFile (no-op without uni-ui); runs on compile"
    )

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

  @transient
  private val uniOpenAPIGenerate = taskKey[Seq[File]]("Fork the OpenAPI generator")

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

          // Fork uni's HttpCodeGeneratorMain on the dependent project classes and dependency JARs,
          // so the generator is the uni the app depends on: the uni this plugin loads in-process
          // is pinned to an older release (adr/2026-09-03-sbt-uni-in-process-uni-pin.md)
          val depClassDirs: Seq[java.io.File] = (Compile / classDirectory)
            .all(dependentProjects)
            .value
          val depClasspath: Seq[java.io.File] = (Compile / dependencyClasspath)
            .value
            .files
            .map(_.toFile)
          val cp = (depClassDirs ++ depClasspath)
            .map(_.getAbsolutePath)
            .mkString(java.io.File.pathSeparator)
          val args =
            Seq("--out", outDir.getAbsolutePath) ++ clients.flatMap(c => Seq("--client", c))
          val exitCode = Fork.java(
            ForkOptions()
              .withJavaHome(javaHome.value)
              .withOutputStrategy(Some(LoggedOutput(log)))
              .withWorkingDirectory(baseDirectory.value),
            Seq("-classpath", cp, "wvlet.uni.http.codegen.HttpCodeGeneratorMain") ++ args
          )
          if exitCode != 0 then
            sys.error(
              s"HTTP client generation failed (exit code ${exitCode}). The project must depend on uni, whose HttpCodeGeneratorMain generates the clients"
            )
          // Every generated file, changed or not, so sbt compiles all of them
          clients.map(spec => generatedClientFile(outDir, spec))
        end if
      },
      // Hook into source generation so generated code is compiled with user code
      Compile / sourceGenerators += uniHttpGenerateClient
    ) ++ uniOpenAPISettings ++ uniUiCssSettings ++ uniRestartSettings

  /**
    * The file HttpCodeGeneratorMain writes for a `TraitName:clientType[:targetPackage]` spec: the
    * target package defaults to the trait's package
    */
  private def generatedClientFile(outDir: File, spec: String): File =
    val parts      = spec.split(":").map(_.trim)
    val apiClass   = parts(0)
    val lastDot    = apiClass.lastIndexOf('.')
    val simpleName = apiClass.substring(lastDot + 1)
    val pkg        =
      if parts.length >= 3 then
        parts(2)
      else if lastDot >= 0 then
        apiClass.substring(0, lastDot)
      else
        ""
    val dir = pkg.split('.').filter(_.nonEmpty).foldLeft(outDir)(_ / _)
    dir / s"${simpleName}Client.scala"

  /**
    * uniOpenAPI forks a JVM that runs uni's OpenAPIGeneratorMain on the project's runtime
    * classpath, so the generator is the uni version the app depends on, and the build never loads
    * application classes.
    */
  private def uniOpenAPISettings: Seq[Setting[?]] = Seq(
    uniOpenAPIRouters             := Seq.empty,
    uniOpenAPITitle               := name.value,
    uniOpenAPIVersion             := version.value,
    uniOpenAPISchemaPackagePrefix := None,
    uniOpenAPIErrorResponses      := Seq(400, 500),
    uniOpenAPIFormat              := "yaml",
    uniOpenAPITargetDir           := target.value / "openapi",
    uniOpenAPIGenerate            := {
      given FileConverter = fileConverter.value
      val log             = streams.value.log
      val format          = uniOpenAPIFormat.value
      val out             = uniOpenAPITargetDir.value / s"openapi.${format}"
      // Classes of this project and of the projects it depends on, without this project's own
      // jar: with exportJars, Runtime classpaths contain it, and this task runs after packageBin
      val classpath = (Compile / products).value ++
        (Compile / internalDependencyClasspath).value.files.map(_.toFile) ++
        (Runtime / externalDependencyClasspath).value.files.map(_.toFile)
      val args =
        uniOpenAPIRouters.value.flatMap(r => Seq("--router", r)) ++
          Seq(
            "--title",
            uniOpenAPITitle.value,
            "--version",
            uniOpenAPIVersion.value,
            "--format",
            format,
            "--error-responses",
            uniOpenAPIErrorResponses.value.mkString(","),
            "--out",
            out.getAbsolutePath
          ) ++
          uniOpenAPISchemaPackagePrefix.value.toSeq.flatMap(p => Seq("--schema-package-prefix", p))
      val cp       = classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
      val exitCode = Fork.java(
        ForkOptions()
          .withJavaHome(javaHome.value)
          .withOutputStrategy(Some(LoggedOutput(log)))
          .withWorkingDirectory(baseDirectory.value),
        Seq("-classpath", cp, "wvlet.uni.http.openapi.OpenAPIGeneratorMain") ++ args
      )
      if exitCode != 0 then
        sys.error(s"OpenAPI generation failed (exit code ${exitCode})")
      log.info(s"Generated ${out}")
      Seq(out)
    },
    // A project without routers resolves no classpath and forks nothing, which matters because
    // the task runs on every package. It regenerates after packaging, as sbt-airframe does;
    // triggering avoids overriding packageBin, whose result sbt 2 caches
    uniOpenAPI :=
      Def
        .taskIf {
          if uniOpenAPIRouters.value.isEmpty then
            Seq.empty[File]
          else
            uniOpenAPIGenerate.value
        }
        .triggeredBy(Compile / packageBin)
        .value
  )

  /**
    * uniUiCss copies uni-ui.css out of the uni-ui jar (or, for a `dependsOn`, its resource
    * directory) on the dependency classpath, so an app's stylesheet can `@import` a file that
    * always matches the uni-ui version it links. It runs after every compile, which Vite's Scala.js
    * plugin triggers on `vite dev` and `vite build`.
    */
  private def uniUiCssSettings: Seq[Setting[?]] = Seq(
    uniUiCssFile := baseDirectory.value / "target" / "uni-ui.css",
    uniUiCss     :=
      Def
        .task {
          given FileConverter = fileConverter.value
          val log             = streams.value.log
          val out             = uniUiCssFile.value
          val classpath       = (Compile / dependencyClasspath).value.files.map(_.toFile)
          UniUiCss.find(classpath) match
            case None =>
              if out.exists() then
                log.warn(
                  s"${UniUiCss.resourcePath} is no longer on the classpath; ${out} is a stale copy"
                )
              else
                log.debug(s"${UniUiCss.resourcePath} is not on the classpath, skipping uniUiCss")
              Seq.empty
            case Some(css) =>
              // Rewrite only on change, so Vite's file watcher does not reload the page needlessly
              if !out.exists() || !java.util.Arrays.equals(IO.readBytes(out), css) then
                IO.write(out, css)
                log.info(s"Copied uni-ui.css to ${out}")
              Seq(out)
        }
        .triggeredBy(Compile / compile)
        .value
  )

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
