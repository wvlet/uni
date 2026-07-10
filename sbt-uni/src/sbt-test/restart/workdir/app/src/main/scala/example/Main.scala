package example

import java.nio.file.{Files, Paths}

/**
  * Test app for `uniRestart`. Writes a marker file named after its first program argument, but only
  * when the system property `uni.test.enabled=true` is set. This lets the scripted test assert that
  * BOTH program args (the file name) AND JVM args after `---` (the system property) are forwarded
  * to the forked JVM. It then stays alive so the test can observe a running background process and
  * later stop it.
  */
object Main:
  def main(args: Array[String]): Unit =
    val name = args.headOption.getOrElse("default")
    if sys.props.get("uni.test.enabled").contains("true") then
      val target = Paths.get("target")
      Files.createDirectories(target)
      Files.write(target.resolve(s"${name}.txt"), "started".getBytes)
    // Stay running so uniStatus/uniStop have a live process to act on.
    Thread.sleep(120000)
