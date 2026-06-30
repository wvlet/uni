// Scripted test: a CrossType.Pure cross-project across JVM / Scala.js / Scala Native, exercising
// shared sources, a cross-version shared dir, platform-specific sources, and a cross dependsOn.

ThisBuild / scalaVersion := "3.8.4"

// `core` cross-builds for all three platforms. The val name `core` becomes the id, so the macro
// must produce sub-projects coreJVM / coreJS / coreNative (referenced from test). The per-platform
// *Settings methods are exercised too (they must reach only the matching platform project).
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(version := "0.1.0")
  .jvmSettings(version := "0.1.0-jvm")
  .jsSettings(version := "0.1.0-js")
  .nativeSettings(version := "0.1.0-native")

// `app` depends on `core` (compile) and `core % Test` on each platform — the same shape uni's real
// build uses (`uni.dependsOn(core, test % Test)`), exercising both the bare-cross-project and the
// `% Test`-scoped CrossClasspathDependency conversions.
lazy val app = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("app"))
  .dependsOn(core, core % Test)

// Verify the per-platform *Settings methods reached only their own platform project.
TaskKey[Unit]("checkVersions") := {
  assert((core.jvm / version).value == "0.1.0-jvm", s"jvm: ${(core.jvm / version).value}")
  assert((core.js / version).value == "0.1.0-js", s"js: ${(core.js / version).value}")
  assert((core.native / version).value == "0.1.0-native", s"native: ${(core.native / version).value}")
}
