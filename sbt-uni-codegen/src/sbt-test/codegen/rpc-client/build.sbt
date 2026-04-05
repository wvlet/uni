val uniVersion = sys.props.getOrElse("uni.version", "0.0.1-SNAPSHOT")

lazy val api = project
  .in(file("api"))
  .settings(
    scalaVersion := "3.8.3",
    libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion
  )

lazy val app = project
  .in(file("app"))
  .enablePlugins(UniHttpCodegenPlugin)
  .settings(
    scalaVersion := "3.8.3",
    uniHttpClients := Seq("example.api.GreetingService:rpc:example.client"),
    libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion
  )
  .dependsOn(api)
