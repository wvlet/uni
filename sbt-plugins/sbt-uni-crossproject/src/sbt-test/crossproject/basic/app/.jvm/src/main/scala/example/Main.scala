package example

// JVM-only entry point (<base>/.jvm/src/main/scala). Verifies that the shared, per-platform, and
// cross-version sources all resolve at runtime, then writes a marker file the scripted test checks.
@main
def appMain(): Unit =
  val r = AppLogic.result
  assert(r == "hello-jvm/scala3", s"unexpected result: ${r}")
  java.nio.file.Files.write(java.nio.file.Paths.get("app-ran.txt"), r.getBytes("UTF-8"))
