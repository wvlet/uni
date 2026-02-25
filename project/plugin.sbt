// Ignore binary incompatible errors for libraries using scala-xml.
// sbt-scoverage upgraded to scala-xml 2.1.0, but other sbt-plugins and Scala compilier 2.12 uses scala-xml 1.x.x
ThisBuild / libraryDependencySchemes ++=
  Seq(
    "org.scala-lang.modules" %% "scala-xml"                % "always",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "always"
  )

val AIRFRAME_VERSION = "2026.1.3"

addSbtPlugin("org.scalameta"       % "sbt-scalafmt"             % "2.5.6")
addSbtPlugin("org.portable-scala"  % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("com.eed3si9n"        % "sbt-buildinfo"            % "0.13.1")
addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings"         % "1.1.3")

// For developing server applications
addSbtPlugin("io.spray" % "sbt-revolver" % "0.10.0")

// For Scala.js
val SCALAJS_VERSION = sys.env.getOrElse("SCALAJS_VERSION", "1.20.2")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % SCALAJS_VERSION)

// For running Scala.js tests in Node.js
libraryDependencies += "org.scala-js" %% "scalajs-env-nodejs" % "1.5.0"

// For running Scala.js tests with JSDOM (provides DOM APIs)
libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"

// For Scala native
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.10")

addDependencyTreePlugin

// For setting explicit versions for each commit
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
addSbtPlugin("org.xerial.sbt" % "sbt-pack"   % "0.23")

scalacOptions ++= Seq("-deprecation", "-feature")
