addSbtPlugin("com.github.sbt" % "sbt-pgp"    % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
// Note: sbt-scalafmt requires sbt 2.0.0-RC11+; re-add it once this build moves off RC10. The
// repo-root .scalafmt.conf style is mirrored here so formatting stays consistent meanwhile.

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
