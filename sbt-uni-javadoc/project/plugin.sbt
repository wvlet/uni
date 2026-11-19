addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.4.0")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.2.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.6.2")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

// Compile this plugin's own sources into its metabuild, so its javadoc uses minimizePluginJavadoc.
// Switch to addSbtPlugin("org.wvlet.uni" % "sbt-uni-javadoc" % ...) after its first release.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala"
