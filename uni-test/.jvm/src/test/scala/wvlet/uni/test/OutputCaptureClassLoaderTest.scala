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
      // The other runner starts first: initializing its Logger resets the root logger's handlers
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

end OutputCaptureClassLoaderTest
