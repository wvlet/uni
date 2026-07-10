// sbt-uni-crossproject: a minimal, uni-owned sbt 2.x re-implementation of sbt-crossproject.
//
// portable-scala/sbt-crossproject (and its sbt-scalajs-crossproject / sbt-scala-native-crossproject
// companions) have not been ported to sbt 2.x. Uni only uses the CrossType.Pure layout, so this
// plugin provides just that surface: crossProject(JVMPlatform, JSPlatform, NativePlatform) with
// shared sources in <base>/src and platform code in <base>/.jvm, .js, .native.

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

// Both plugins are published for sbt 2.x under the `_sbt2_3` coordinate suffix. The plugin enables
// ScalaJSPlugin / ScalaNativePlugin on the JS / Native platform projects, so it compiles against
// these and consumers get them transitively.
val SCALAJS_VERSION      = "1.22.0"
val SCALA_NATIVE_VERSION = "0.5.12"

lazy val sbtUniCrossProject = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name        := "sbt-uni-crossproject",
    description := "Minimal sbt 2.x crossproject plugin (CrossType.Pure) for uni",
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
      ),
    addSbtPlugin("org.scala-js"     % "sbt-scalajs"      % SCALAJS_VERSION),
    addSbtPlugin("org.scala-native" % "sbt-scala-native" % SCALA_NATIVE_VERSION),
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++ Seq("-Xmx1024M", s"-Dplugin.version=${version.value}")
    },
    scriptedBufferLog := false
  )
