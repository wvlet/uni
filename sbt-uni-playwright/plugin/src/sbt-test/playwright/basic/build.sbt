import org.scalajs.linker.interface.ModuleKind

lazy val app = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin, UniPlaywrightPlugin)
  .settings(
    scalaVersion := "3.8.2",
    // The whole point: run ES-module Scala.js in a real browser.
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
    // UniPlaywrightPlugin sets Test / jsEnv automatically; just pick the browser.
    uniPlaywrightBrowser  := "chromium",
    uniPlaywrightHeadless := true,
    // On sbt 2.x, `%%` encodes both the Scala version and the Scala.js platform suffix (_sjs1),
    // so the old `%%%` operator is no longer used.
    libraryDependencies ++=
      Seq("org.scala-js" %% "scalajs-dom" % "2.8.1", "org.scalameta" %% "munit" % "1.1.0" % Test),
    testFrameworks += new TestFramework("munit.Framework")
  )
