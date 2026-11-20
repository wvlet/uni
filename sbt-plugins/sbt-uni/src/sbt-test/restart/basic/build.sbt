// Passed by sbt-uni's scriptedLaunchOpts: the Scala version uni (and so this app) compiles with.
val scala3 = sys
  .props
  .getOrElse(
    "scala.version",
    sys.error(
      "The system property 'scala.version' is not defined. Run this test through sbt-uni's `scripted`, which passes it via scriptedLaunchOpts."
    )
  )

lazy val app = project.in(file("app")).enablePlugins(UniPlugin).settings(scalaVersion := scala3)
