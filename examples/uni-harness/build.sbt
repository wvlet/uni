// uni-harness: a chat workspace shell (icon rail, foldable sidebar, chat, right dock) built with
// Uni + Scala.js, running as an Electron app and as a web app from the same code.
//
// Three Scala.js modules:
//   - api:      the shared ChatApi RPC trait and models
//   - server:   the ChatApi implementation, exposed over Electron IPC and over Node HTTP
//   - renderer: the UI, calling ChatApi over IPC (Electron) or fetch (browser)
//
// The Uni version defaults to a locally published snapshot; override with
//   sbt -Duni.version=<version>
val uniVersion = sys.props.getOrElse("uni.version", "2026.1.39-SNAPSHOT")
val scala3     = "3.9.0"

ThisBuild / scalaVersion := scala3

lazy val commonSettings = Seq(
  scalaVersion := scala3,
  // Emit ES modules so Vite / electron-vite can import them.
  scalaJSLinkerConfig ~= {
    _.withModuleKind(ModuleKind.ESModule)
  },
  // The modules export functions instead of running a `main` entry point.
  scalaJSUseMainModuleInitializer         := false,
  libraryDependencies += "org.wvlet.uni" %%% "uni" % uniVersion
)

lazy val api = project.in(file("api")).enablePlugins(ScalaJSPlugin).settings(commonSettings)

lazy val server = project
  .in(file("server"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .dependsOn(api)

lazy val renderer = project
  .in(file("renderer"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .dependsOn(api)
