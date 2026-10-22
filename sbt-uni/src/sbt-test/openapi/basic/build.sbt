val uniVersion = sys.props.getOrElse("uni.version", "0.0.1-SNAPSHOT")
// Passed by sbt-uni's scriptedLaunchOpts: the Scala version uni (and so this app) compiles with.
val scala3 = sys
  .props
  .getOrElse(
    "scala.version",
    sys.error(
      "The system property 'scala.version' is not defined. Run this test through sbt-uni's `scripted`, which passes it via scriptedLaunchOpts."
    )
  )

lazy val app = project
  .in(file("app"))
  .enablePlugins(UniPlugin)
  .settings(
    scalaVersion                           := scala3,
    version                                := "1.2.3",
    libraryDependencies += "org.wvlet.uni" %% "uni" % uniVersion,
    uniOpenAPIRouters                      := Seq("example.Api.router"),
    uniOpenAPITitle                        := "Example API",
    uniOpenAPISchemaPackagePrefix          := Some("example."),
    uniOpenAPITargetDir                    := baseDirectory.value / "doc"
  )

lazy val checkOpenAPI = taskKey[Unit]("Check the generated openapi.yaml")

app / checkOpenAPI := {
  val yaml = IO.read((app / baseDirectory).value / "doc" / "openapi.yaml")
  Seq("Example API", "1.2.3", "/greet/{name}:", "Greet someone", "Greeting:")
    .foreach { s =>
      if (!yaml.contains(s)) sys.error(s"openapi.yaml does not contain ${s}:\n${yaml}")
    }
}
