addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.3.2")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.1.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.6.2")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

// Strips fonts and inkuire.js from Scaladoc output to keep -javadoc.jar small (scala/scala3#26387)
addSbtPlugin("com.eed3si9n" % "sbt-salad-days" % "0.2.0")

// Compile the root build's MinimizeJavadoc into this build, so the plugins share its javadoc page
Compile / unmanagedSources +=
  baseDirectory.value.getParentFile.getParentFile / "project" / "MinimizeJavadoc.scala"
