// sbt-uni: sbt 2.x plugin for uni
// Written in Scala 3 (sbt 2.x metabuild), directly calls uni as an in-process library.

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

// The uni version the scripted test apps depend on (the locally published snapshot in CI).
val UNI_VERSION = sys.env.getOrElse("UNI_VERSION", "0.0.1-SNAPSHOT")

// uni runs in-process inside sbt's metabuild, so it must be readable by the metabuild's Scala
// compiler. sbt 2.0.x compiles the metabuild with Scala 3.8, which cannot read TASTy produced by
// Scala 3.9 (uni's current SCALA_3). Pin the plugin's in-process uni to the last release built with
// Scala 3.8 until an sbt release moves the metabuild to Scala 3.9 (sbt's develop branch already has
// scala3 = "3.9.0"); then this can go back to UNI_VERSION.
// See adr/2026-09-03-sbt-uni-in-process-uni-pin.md
val UNI_IN_PROCESS_VERSION = "2026.1.21"

lazy val sbtUni = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name        := "sbt-uni",
    description := "sbt plugin for uni",
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
    // uni runs in-process (Scala 3 metabuild enables this)
    libraryDependencies ++= Seq("org.wvlet.uni" %% "uni" % UNI_IN_PROCESS_VERSION),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", s"-Dplugin.version=${version.value}", s"-Duni.version=${UNI_VERSION}")
    },
    scriptedBufferLog := false
  )
