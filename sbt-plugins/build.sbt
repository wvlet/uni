// sbt 2.x plugins published from the uni repository, built together in one sbt build so that CI and
// the release start sbt once for all of them. The uni libraries are built by the root build.sbt.
//
// - sbt-uni: uniRestart/uniStop/uniStatus, HTTP/RPC client code generation, and OpenAPI generation
// - sbt-uni-crossproject: crossProject(JVMPlatform, JSPlatform, NativePlatform) for CrossType.Pure
// - sbt-uni-javadoc: shrinks -javadoc.jar to stay within Maven Central's publishing limits
// - uni-jsenv-playwright and sbt-uni-playwright: run Scala.js tests in a headless browser

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

// The guides that each plugin's link-only javadoc page (minimizePluginJavadoc) points to
ThisBuild / javadocSiteUrl := "https://wvlet.org/uni/"

// Use dynamic snapshot version strings for non tagged versions
ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / dynverSeparator         := "-"

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if isSnapshot.value then
    Some("central-snapshots" at centralSnapshots)
  else
    localStaging.value
}

// Stage releases into the uni root build's staging directory, so a single `sonaRelease` from the
// root uploads the plugins together with every other uni artifact as one Sonatype deployment.
// Global scope, because sbt defines `localStaging` (which `publishTo` uses) as `Global / localStaging`.
Global / stagingDirectory :=
  (ThisBuild / baseDirectory).value.getParentFile / "target" / "sona-staging"

val commonSettings = Seq[Setting[?]](
  licenses += ("Apache-2.0", uri("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(uri("https://github.com/wvlet/uni")),
  scmInfo  :=
    Some(
      ScmInfo(
        browseUrl = uri("https://github.com/wvlet/uni"),
        connection = "scm:git:git@github.com:wvlet/uni.git"
      )
    ),
  developers :=
    List(
      Developer(
        id = "leo",
        name = "Taro L. Saito",
        email = "leo@xerial.org",
        url = uri("http://xerial.org/leo")
      )
    )
)

val scriptedSettings = Seq[Setting[?]](
  scriptedLaunchOpts := {
    scriptedLaunchOpts.value ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}")
  },
  scriptedBufferLog := false
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "uni-sbt-plugins",
    // Stage only the five plugin artifacts, not this empty aggregate
    publishArtifact       := false,
    publish               := {},
    publishLocal          := {},
    PgpKeys.publishSigned := {},
    publish / skip        := true
  )
  .aggregate(sbtUni, sbtUniCrossProject, sbtUniJavadoc, jsenv, sbtUniPlaywright)

// --- sbt-uni ---

// The uni version the scripted test apps depend on (the locally published snapshot in CI).
val UNI_VERSION = sys.env.getOrElse("UNI_VERSION", "0.0.1-SNAPSHOT")

// The Scala version the scripted test apps compile with: uni's own SCALA_3, read from the root
// build.sbt, because the apps must be on uni's Scala minor to read that snapshot's TASTy.
val UNI_SCALA_VERSION: String =
  val rootBuild = IO.read(file("..") / "build.sbt")
  """val SCALA_3\s*=\s*"([^"]+)""""
    .r
    .findFirstMatchIn(rootBuild)
    .map(_.group(1))
    .getOrElse(sys.error("""Could not find `val SCALA_3 = "..."` in the root build.sbt"""))

// The uni the plugin calls in-process. Pinned to the last uni release built with sbt 2.0.x's
// metabuild Scala (3.8); uni itself now compiles with 3.9, whose TASTy that metabuild cannot read.
// Unlike UNI_PLUGIN_VERSION in the root project/plugin.sbt, this must not advance to a 3.9-built
// release. Revert to UNI_VERSION once sbt's metabuild moves to Scala 3.9.
// See adr/2026-09-03-sbt-uni-in-process-uni-pin.md
// scala-steward:off
val UNI_IN_PROCESS_VERSION = "2026.1.21"
// scala-steward:on

lazy val sbtUni = project
  .in(file("sbt-uni"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings, scriptedSettings)
  .settings(
    name        := "sbt-uni",
    description :=
      "sbt 2.x plugin for Uni: uniRestart/uniStop/uniStatus to run servers in the background during development, HTTP/RPC client code generation from service traits, and OpenAPI spec generation from Uni routers",
    // uni runs in-process (Scala 3 metabuild enables this)
    libraryDependencies ++= Seq("org.wvlet.uni" %% "uni" % UNI_IN_PROCESS_VERSION),
    scriptedLaunchOpts ++=
      Seq(s"-Duni.version=${UNI_VERSION}", s"-Dscala.version=${UNI_SCALA_VERSION}"),
    minimizePluginJavadoc(guide = "build/sbt-uni", sourceDir = "sbt-plugins/sbt-uni/src/main/scala")
  )

// --- sbt-uni-crossproject ---
// A minimal, uni-owned sbt 2.x re-implementation of sbt-crossproject. portable-scala/sbt-crossproject
// (and its sbt-scalajs-crossproject / sbt-scala-native-crossproject companions) have not been ported
// to sbt 2.x. Uni only uses the CrossType.Pure layout, so this plugin provides just that surface:
// crossProject(JVMPlatform, JSPlatform, NativePlatform) with shared sources in <base>/src and
// platform code in <base>/.jvm, .js, .native.

// Both plugins are published for sbt 2.x under the `_sbt2_3` coordinate suffix. The plugin enables
// ScalaJSPlugin / ScalaNativePlugin on the JS / Native platform projects, so it compiles against
// these and consumers get them transitively.
val CROSSPROJECT_SCALAJS_VERSION = "1.22.0"
val SCALA_NATIVE_VERSION         = "0.5.12"

lazy val sbtUniCrossProject = project
  .in(file("sbt-uni-crossproject"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings, scriptedSettings)
  .settings(
    name        := "sbt-uni-crossproject",
    description :=
      "Minimal sbt 2.x crossProject plugin (CrossType.Pure) for building Scala 3 code for JVM, Scala.js, and Scala Native; replaces sbt-scalajs-crossproject and sbt-scala-native-crossproject",
    addSbtPlugin("org.scala-js"     % "sbt-scalajs"      % CROSSPROJECT_SCALAJS_VERSION),
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % SCALA_NATIVE_VERSION),
    minimizePluginJavadoc(
      guide = "build/sbt-uni-crossproject",
      sourceDir = "sbt-plugins/sbt-uni-crossproject/src/main/scala"
    )
  )

// --- sbt-uni-javadoc ---
// See docs/build/sbt-uni-javadoc.md

// Strips fonts and inkuire.js from Scaladoc output. Users get it transitively, so one addSbtPlugin
// gives them every reduction.
val SALAD_DAYS_VERSION = "0.2.0"

lazy val sbtUniJavadoc = project
  .in(file("sbt-uni-javadoc"))
  .enablePlugins(SbtPlugin)
  .settings(commonSettings, scriptedSettings)
  .settings(
    name        := "sbt-uni-javadoc",
    description :=
      "sbt 2.x plugin that shrinks -javadoc.jar (dynamic Scaladoc side menu, link-only javadoc for Scala.js/Native and sbt plugin artifacts) to stay within Maven Central's publishing limits",
    addSbtPlugin("com.eed3si9n" % "sbt-salad-days" % SALAD_DAYS_VERSION),
    minimizePluginJavadoc(
      guide = "build/sbt-uni-javadoc",
      sourceDir = "sbt-plugins/sbt-uni-javadoc/src/main/scala"
    )
  )

// --- uni-jsenv-playwright and sbt-uni-playwright ---
// A uni-owned Scala.js JSEnv backed by Java Playwright, plus an sbt 2.x plugin. Replaces the
// unmaintained, Scala-2.12-only, cats-effect-based io.github.gmkumar2005 env so uni can run Scala.js
// tests in a real ES-module browser on sbt 2.x without those constraints.

// The Java Playwright runtime. It bundles its own driver and downloads browsers on demand, so no
// Node.js or npm package is required to run the tests.
val PLAYWRIGHT_VERSION = "1.49.0"
// The Scala.js JSEnv API (org.scalajs.jsenv.*). Versioned independently of the sbt-scalajs plugin.
val SCALAJS_JS_ENVS_VERSION = "1.6.0"

// Match the Scala version sbt 2.x uses for the metabuild, so the published JSEnv library and the
// plugin are the same Scala 3 binary version and consumers can load the library in project/.
val JSENV_SCALA_VERSION = "3.8.2"

// The JSEnv library. Consumed in a build's metabuild (project/plugins.sbt) and referenced as
// `Test / jsEnv := new wvlet.uni.jsenv.playwright.PlaywrightJSEnv(...)`, just like the official
// scalajs-env-jsdom-nodejs. Published as a plain Scala 3 artifact.
lazy val jsenv = project
  .in(file("sbt-uni-playwright/jsenv"))
  .settings(commonSettings)
  .settings(
    name        := "uni-jsenv-playwright",
    description :=
      "Scala.js JSEnv that runs tests in a real headless browser (Chromium, Firefox, or WebKit) via Java Playwright, without Node.js or npm",
    scalaVersion := JSENV_SCALA_VERSION,
    libraryDependencies ++=
      Seq(
        "org.scala-js"            %% "scalajs-js-envs" % SCALAJS_JS_ENVS_VERSION,
        "com.microsoft.playwright" % "playwright"      % PLAYWRIGHT_VERSION,
        "org.scalameta"           %% "munit"           % "1.1.0" % Test
      ),
    minimizePluginJavadoc(
      guide = "build/sbt-uni-playwright",
      sourceDir = "sbt-plugins/sbt-uni-playwright/jsenv/src/main/scala"
    )
  )

// The sbt 2.x plugin. Bundles the JSEnv and adds ergonomic settings plus a browser-install task.
lazy val sbtUniPlaywright = project
  .in(file("sbt-uni-playwright/plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(jsenv)
  .settings(commonSettings, scriptedSettings)
  .settings(
    name        := "sbt-uni-playwright",
    description :=
      "sbt 2.x plugin that runs Scala.js tests in a real headless browser (Chromium, Firefox, or WebKit) via Playwright, with full DOM and ES module support",
    // Depend on sbt-scalajs so the plugin can set the `Test / jsEnv` key for consumers.
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.23.0"),
    minimizePluginJavadoc(
      guide = "build/sbt-uni-playwright",
      sourceDir = "sbt-plugins/sbt-uni-playwright/plugin/src/main/scala"
    )
  )
