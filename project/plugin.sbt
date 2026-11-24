// uni-owned sbt 2.x plugins (published from this repo) that replace third-party plugins which have
// not been ported to sbt 2.x: sbt-uni-crossproject replaces sbt-scalajs-crossproject +
// sbt-scala-native-crossproject; uni-jsenv-playwright replaces io.github.gmkumar2005's env; sbt-uni
// (uniRestart/uniStop/uniStatus) replaces sbt-revolver.
val UNI_PLUGIN_VERSION = "2026.1.41"

// For GPG signing published artifacts
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.4.0")

addSbtPlugin("org.scalameta"       % "sbt-scalafmt"     % "2.6.2")
addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.4")

// Cross-building for JVM / Scala.js / Scala Native (CrossType.Pure)
addSbtPlugin("org.wvlet.uni" % "sbt-uni-crossproject" % UNI_PLUGIN_VERSION)

// uniRestart/uniStop/uniStatus for developing server applications
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % UNI_PLUGIN_VERSION)

// For Scala.js
val SCALAJS_VERSION = sys.env.getOrElse("SCALAJS_VERSION", "1.22.0")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % SCALAJS_VERSION)

// For running Scala.js tests in Node.js
libraryDependencies += "org.scala-js" %% "scalajs-env-nodejs" % "1.7.0"

// For running Scala.js DOM tests in a real headless browser (Chromium via Playwright). Unlike
// jsdom, this supports ES modules and a faithful DOM, and Chromium matches the Electron renderer.
// The Java Playwright runtime bundles its own driver and downloads browsers on demand, so no
// Node.js or npm packages are required to run these tests.
libraryDependencies += "org.wvlet.uni" %% "uni-jsenv-playwright" % UNI_PLUGIN_VERSION

// For Scala native
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13")

// For setting explicit versions for each commit
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.2.0")
addSbtPlugin("org.xerial.sbt" % "sbt-pack"   % "1.0.1")

scalacOptions ++= Seq("-deprecation", "-feature")

// Strips fonts and inkuire.js from Scaladoc output to keep -javadoc.jar small (scala/scala3#26387)
addSbtPlugin("com.eed3si9n" % "sbt-salad-days" % "0.2.0")

// minimizeScaladoc, minimizeJavadoc, and minimizePluginJavadoc (sbt-uni-javadoc), compiled from
// source because uni plugins resolve at UNI_PLUGIN_VERSION, which predates sbt-uni-javadoc. After
// its first release, replace this and sbt-salad-days above (a dependency of sbt-uni-javadoc) with
// addSbtPlugin("org.wvlet.uni" % "sbt-uni-javadoc" % UNI_PLUGIN_VERSION).
Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "sbt-plugins" / "sbt-uni-javadoc" / "src" / "main" / "scala"
