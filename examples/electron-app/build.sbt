// Example Electron desktop app built with Uni + Scala.js.
//
// Three Scala.js modules:
//   - api:      the shared RPC service definition (trait + models), used by both processes
//   - main:     the Electron main process (service implementation + IPC wiring)
//   - renderer: the Electron renderer (UI + RPC client over IPC)
//
// The Uni version defaults to the locally published snapshot; override with
//   sbt -Duni.version=<version>
val uniVersion = sys.props.getOrElse("uni.version", "2026.1.13-SNAPSHOT")
val scala3     = "3.8.4"

ThisBuild / scalaVersion := scala3

lazy val commonSettings = Seq(
  scalaVersion := scala3,
  // Emit ES modules so Vite / electron-vite can import them.
  scalaJSLinkerConfig ~= {
    _.withModuleKind(ModuleKind.ESModule)
  },
  // Neither module has a `main` entry point; both export functions instead.
  scalaJSUseMainModuleInitializer := false,
  libraryDependencies += "org.wvlet.uni" %%% "uni" % uniVersion
)

lazy val api = project
  .in(file("api"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)

lazy val main = project
  .in(file("main"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .dependsOn(api)

lazy val renderer = project
  .in(file("renderer"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .dependsOn(api)
