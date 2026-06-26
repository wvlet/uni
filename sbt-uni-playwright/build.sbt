// sbt-uni-playwright: a uni-owned Scala.js JSEnv backed by Java Playwright, plus an sbt 2.x plugin.
// Replaces the unmaintained, Scala-2.12-only, cats-effect-based io.github.gmkumar2005 env so uni
// can run Scala.js tests in a real ES-module browser on sbt 2.x without those constraints.

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / dynverSeparator         := "-"

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value)
    Some("central-snapshots" at centralSnapshots)
  else
    localStaging.value
}

// The Java Playwright runtime. It bundles its own driver and downloads browsers on demand, so no
// Node.js or npm package is required to run the tests.
val PLAYWRIGHT_VERSION = "1.49.0"
// The Scala.js JSEnv API (org.scalajs.jsenv.*). Versioned independently of the sbt-scalajs plugin.
val SCALAJS_JS_ENVS_VERSION = "1.6.0"

// Match the Scala version sbt 2.x uses for the metabuild, so the published JSEnv library and the
// plugin are the same Scala 3 binary version and consumers can load the library in project/.
val SCALA_3 = "3.8.2"

val commonSettings = Seq[Setting[?]](
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/wvlet/uni")),
  scmInfo  :=
    Some(
      ScmInfo(
        browseUrl = url("https://github.com/wvlet/uni"),
        connection = "scm:git:git@github.com:wvlet/uni.git"
      )
    ),
  developers :=
    List(
      Developer(
        id = "leo",
        name = "Taro L. Saito",
        email = "leo@xerial.org",
        url = url("http://xerial.org/leo")
      )
    )
)

// The JSEnv library. Consumed in a build's metabuild (project/plugins.sbt) and referenced as
// `Test / jsEnv := new wvlet.uni.jsenv.playwright.PlaywrightJSEnv(...)`, just like the official
// scalajs-env-jsdom-nodejs. Published as a plain Scala 3 artifact.
lazy val jsenv = project
  .in(file("jsenv"))
  .settings(commonSettings)
  .settings(
    name         := "uni-jsenv-playwright",
    description  := "A Scala.js JSEnv that runs tests in a real browser via Java Playwright",
    scalaVersion := SCALA_3,
    libraryDependencies ++=
      Seq(
        "org.scala-js"             %% "scalajs-js-envs" % SCALAJS_JS_ENVS_VERSION,
        "com.microsoft.playwright" % "playwright"       % PLAYWRIGHT_VERSION
      )
  )

// The sbt 2.x plugin. Bundles the JSEnv and adds ergonomic settings plus a browser-install task.
lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(jsenv)
  .settings(commonSettings)
  .settings(
    name        := "sbt-uni-playwright",
    description := "sbt 2.x plugin: run Scala.js tests in a real browser via Playwright",
    // Depend on sbt-scalajs so the plugin can set the `Test / jsEnv` key for consumers.
    addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0"),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}")
    },
    scriptedBufferLog := false
  )

lazy val root = project
  .in(file("."))
  .settings(publish / skip := true)
  .aggregate(jsenv, plugin)
