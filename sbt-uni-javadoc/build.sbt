// sbt-uni-javadoc: sbt 2.x plugin that shrinks -javadoc.jar to stay within Maven Central's
// per-organization publishing limits. See docs/build/sbt-uni-javadoc.md.

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

// The guides that the plugin's link-only javadoc page (minimizePluginJavadoc) points to
ThisBuild / javadocSiteUrl := "https://wvlet.org/uni/"

ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / dynverSeparator         := "-"

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value)
    Some("central-snapshots" at centralSnapshots)
  else
    localStaging.value
}

// Stage releases into the uni root build's staging directory, so a single `sonaRelease` from the
// root uploads this plugin together with every other uni artifact as one Sonatype deployment.
// Global scope, because sbt defines `localStaging` (which `publishTo` uses) as `Global / localStaging`.
Global / stagingDirectory :=
  (ThisBuild / baseDirectory).value.getParentFile / "target" / "sona-staging"

// Strips fonts and inkuire.js from Scaladoc output. Users get it transitively, so one addSbtPlugin
// gives them every reduction.
val SALAD_DAYS_VERSION = "0.2.0"

lazy val sbtUniJavadoc = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name        := "sbt-uni-javadoc",
    description :=
      "sbt 2.x plugin that shrinks -javadoc.jar (dynamic Scaladoc side menu, link-only javadoc for Scala.js/Native and sbt plugin artifacts) to stay within Maven Central's publishing limits",
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
      ),
    addSbtPlugin("com.eed3si9n" % "sbt-salad-days" % SALAD_DAYS_VERSION),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}")
    },
    scriptedBufferLog := false,
    minimizePluginJavadoc(
      guide = "build/sbt-uni-javadoc",
      sourceDir = "sbt-uni-javadoc/src/main/scala"
    )
  )
