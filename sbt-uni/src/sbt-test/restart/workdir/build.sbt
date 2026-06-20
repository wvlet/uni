lazy val app = project
  .in(file("app"))
  .enablePlugins(UniPlugin)
  .settings(
    scalaVersion := "3.8.3",
    // Start the forked process from a subdirectory instead of the project root.
    uniRestart / baseDirectory := baseDirectory.value / "run-dir"
  )
