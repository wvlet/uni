addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.4.0")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.2.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.6.2")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

// Compile sbt-uni-javadoc (minimizePluginJavadoc) from source, as it is not published yet. After its
// first release, replace this with addSbtPlugin("org.wvlet.uni" % "sbt-uni-javadoc" % ...).
Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "sbt-uni-javadoc" / "src" / "main" / "scala"
