val uniVersion = sys.props.getOrElse("uni.version", "0.0.1-SNAPSHOT")
// Passed by sbt-uni's scriptedLaunchOpts: the Scala version uni (and so this app) compiles with.
val scala3 = sys.props("scala.version")

lazy val api = project
  .in(file("api"))
  .settings(scalaVersion := scala3, libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion)

lazy val app = project
  .in(file("app"))
  .enablePlugins(UniPlugin)
  .settings(
    scalaVersion                           := scala3,
    uniHttpClients                         := Seq("example.api.GreetingService:rpc:example.client"),
    libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion
  )
  .dependsOn(api)
