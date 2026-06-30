addSbtPlugin("com.github.sbt" % "sbt-pgp"    % "2.3.1")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
// Note: sbt-scalafmt (which needs sbt 2.0.0-RC11+) can now be re-added, since this build is on
// sbt 2.0.1 — left as a follow-up. The repo-root .scalafmt.conf style is mirrored here so
// formatting stays consistent meanwhile.

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
