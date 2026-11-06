// Passed by sbt-uni's scriptedLaunchOpts: the Scala version uni (and so this app) compiles with.
val scala3 = sys
  .props
  .getOrElse(
    "scala.version",
    sys.error(
      "The system property 'scala.version' is not defined. Run this test through sbt-uni's `scripted`, which passes it via scriptedLaunchOpts."
    )
  )

ThisBuild / scalaVersion := scala3

// Stands in for the uni-ui artifact: a module holding the stylesheet at the same resource path
lazy val ui = project.in(file("ui"))

// Takes uni-ui.css from a dependsOn, and writes it where its web assets live
lazy val app = project
  .in(file("app"))
  .enablePlugins(UniPlugin)
  .settings(uniUiCssFile := baseDirectory.value / "web" / "uni-ui.css")
  .dependsOn(ui)

// Uses sbt-uni without uni-ui: nothing is copied
lazy val plain = project
  .in(file("plain"))
  .enablePlugins(UniPlugin)

lazy val checkCss = taskKey[Unit]("Check the copied uni-ui.css")

app / checkCss := {
  val copied   = IO.read((app / baseDirectory).value / "web" / "uni-ui.css")
  val original = IO.read((ui / baseDirectory).value / "src/main/resources/wvlet/uni/ui/uni-ui.css")
  if (copied != original) sys.error(s"Copied uni-ui.css differs from the original:\n${copied}")
}
