// sbt-uni-codegen: sbt 2.x plugin for generating HTTP/RPC client code
// This plugin is written in Scala 3 (sbt 2.x metabuild) and directly calls
// uni-http-codegen as an in-process library — no forked JVM needed.

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / organization := "org.wvlet.uni"

// Use dynamic snapshot version strings for non tagged versions
ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / dynverSeparator         := "-"

ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value)
    Some("central-snapshots" at centralSnapshots)
  else
    localStaging.value
}

// Read uni-http-codegen version from environment or use snapshot
val UNI_VERSION = sys.env.getOrElse("UNI_VERSION", "0.0.1-SNAPSHOT")

lazy val sbtUniCodegen = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name        := "sbt-uni-codegen",
    description := "sbt plugin for generating HTTP/RPC client code from Scala 3 traits",
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("https://github.com/wvlet/uni")),
    scmInfo :=
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
      ),
    // uni runs in-process (Scala 3 metabuild enables this)
    // Codegen logic lives in uni's JVM-specific code (wvlet.uni.http.codegen)
    libraryDependencies ++= Seq(
      "org.wvlet.uni" %% "uni" % UNI_VERSION
    ),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq(
        "-Xmx1024M",
        s"-Dplugin.version=${version.value}",
        s"-Duni.version=${UNI_VERSION}"
      )
    },
    scriptedBufferLog := false
  )
