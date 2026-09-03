// Passed by sbt-uni's scriptedLaunchOpts: the Scala version uni (and so this app) compiles with.
val scala3 = sys.props("scala.version")

lazy val app = project.in(file("app")).enablePlugins(UniPlugin).settings(scalaVersion := scala3)
