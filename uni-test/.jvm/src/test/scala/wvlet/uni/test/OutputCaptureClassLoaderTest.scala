/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.test

import java.net.URLClassLoader
import java.util.logging as jl

/**
  * sbt runs the tests of several projects in one JVM, each project's runner with its own copy of
  * uni-test and uni-core. Each copy of TestLogCapture wraps the root logger's handlers, so their
  * routers nest.
  */
class OutputCaptureClassLoaderTest extends UniTest:

  /** A second copy of uni-test's classes, as another project's runner loads them. */
  private def otherRunnerLoader(): URLClassLoader =
    val classes = Seq(
      classOf[spi.TestLogCapture.type],
      classOf[wvlet.uni.log.Logger],
      classOf[scala.Option[?]],
      classOf[scala.runtime.LazyVals.type],
      classOf[sbt.testing.Logger]
    )
    val urls = classes.map(_.getProtectionDomain.getCodeSource.getLocation).distinct
    URLClassLoader(urls.toArray, ClassLoader.getPlatformClassLoader)

  private def moduleOf(loader: ClassLoader, name: String): AnyRef = loader
    .loadClass(s"${name}$$")
    .getField("MODULE$")
    .get(null)

  test("capture a pool thread's record while another runner's copy captures too") {
    val root   = jl.Logger.getLogger("")
    val saved  = root.getHandlers
    val loader = otherRunnerLoader()
    try
      // The other runner starts first
      val otherLogger = moduleOf(loader, "wvlet.uni.log.Logger")
      otherLogger.getClass.getMethod("rootLogger").invoke(otherLogger)
      val other = moduleOf(loader, "wvlet.uni.test.spi.TestLogCapture")
      // The other copy's own scala.None, since its Option is another class
      val none        = moduleOf(loader, "scala.None")
      val openCapture = other.getClass.getMethod("openCapture", loader.loadClass("scala.Option"))
      val logs        = captureLogs {
        // A suite of the other runner captures every logger, and its router wraps this copy's
        val otherCapture = openCapture.invoke(other, none)
        try
          val t = Thread(() => CaptureNoisy().fail())
          t.start()
          t.join()
        finally
          otherCapture.getClass.getMethod("close", classOf[Boolean]).invoke(otherCapture, false)
      }
      logs.warnings shouldContain "handler failed"
    finally
      root.getHandlers.foreach(root.removeHandler)
      saved.foreach(root.addHandler)
      loader.close()
  }

  test("keep capturing when another runner's copy initializes its Logger") {
    val root   = jl.Logger.getLogger("")
    val saved  = root.getHandlers
    val loader = otherRunnerLoader()
    try
      val logs = captureLogs {
        // Another project's runner starts while this runner's suite captures
        val otherLogger = moduleOf(loader, "wvlet.uni.log.Logger")
        otherLogger.getClass.getMethod("rootLogger").invoke(otherLogger)
        CaptureNoisy().fail()
      }
      logs.warnings shouldContain "handler failed"
    finally
      root.getHandlers.foreach(root.removeHandler)
      saved.foreach(root.addHandler)
      loader.close()
  }

  test("replace a handler of another copy that is not a live router") {
    val root     = jl.Logger.getLogger("")
    val saved    = root.getHandlers
    val finished = otherRunnerLoader()
    val next     = otherRunnerLoader()
    try
      // A runner that has finished left its console handler on the root logger
      val env     = moduleOf(finished, "wvlet.uni.log.LogEnv")
      val handler = env.getClass.getMethod("defaultHandler").invoke(env).asInstanceOf[jl.Handler]
      root.getHandlers.foreach(root.removeHandler)
      root.addHandler(handler)
      val nextLogger = moduleOf(next, "wvlet.uni.log.Logger")
      nextLogger.getClass.getMethod("rootLogger").invoke(nextLogger)
      // The next runner's Logger replaces it, so the finished runner's classes are not kept in use
      root.getHandlers.exists(_ eq handler) shouldBe false
      root.getHandlers.map(_.getClass.getClassLoader).toSeq shouldBe Seq(next)
    finally
      root.getHandlers.foreach(root.removeHandler)
      saved.foreach(root.addHandler)
      finished.close()
      next.close()
  }

  test("keep a suite thread's record away from another runner's capture") {
    val root   = jl.Logger.getLogger("")
    val saved  = root.getHandlers
    val loader = otherRunnerLoader()
    try
      val otherLogger = moduleOf(loader, "wvlet.uni.log.Logger")
      otherLogger.getClass.getMethod("rootLogger").invoke(otherLogger)
      val other       = moduleOf(loader, "wvlet.uni.test.spi.TestLogCapture")
      val none        = moduleOf(loader, "scala.None")
      val openCapture = other.getClass.getMethod("openCapture", loader.loadClass("scala.Option"))
      // The other runner's capture opens first, so this copy's router wraps it
      val otherCapture         = openCapture.invoke(other, none)
      var otherRecords: AnyRef = null
      try captureLogs {
          // A suite of this runner logs on its own thread, with its sbt loggers set
          val t = Thread(() =>
            spi
              .TestLogCapture
              .withLoggers(Array.empty[sbt.testing.Logger]) {
                spi.TestLogCapture.withSuiteThread(CaptureNoisy().fail())
              }
          )
          t.start()
          t.join()
        }
      finally
        otherRecords = otherCapture
          .getClass
          .getMethod("close", classOf[Boolean])
          .invoke(otherCapture, false)
      // Only this runner's suite owns the record
      otherRecords.getClass.getMethod("isEmpty").invoke(otherRecords) shouldBe
        java.lang.Boolean.TRUE
    finally
      root.getHandlers.foreach(root.removeHandler)
      saved.foreach(root.addHandler)
      loader.close()
    end try
  }

end OutputCaptureClassLoaderTest
