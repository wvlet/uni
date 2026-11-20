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
package wvlet.uni.jsenv.playwright

import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/**
  * Writes generated content (the JS shim, the launcher HTML) to real temp files so the browser can
  * load them over `file://`, and deletes them on [[close]]. Linked Scala.js inputs are referenced
  * in place (their own paths), so only generated files are tracked here.
  */
private[playwright] class Materializer extends AutoCloseable:
  private var tmpFiles: List[Path] = Nil

  /**
    * Write `content` to a fresh temp file ending in `suffix` (e.g. ".js", ".html") and return its
    * URL.
    */
  def write(suffix: String, content: String): URL =
    val tmp = Files.createTempFile("uni-playwright-", suffix)
    // Track before writing so a write failure still leaves the temp file scheduled for cleanup.
    synchronized {
      tmpFiles = tmp :: tmpFiles
    }
    Files.write(tmp, content.getBytes(StandardCharsets.UTF_8))
    tmp.toUri.toURL

  override def close(): Unit =
    val files = synchronized {
      val fs = tmpFiles
      tmpFiles = Nil
      fs
    }
    files.foreach { f =>
      try
        Files.deleteIfExists(f)
      catch
        case NonFatal(_) => // best-effort cleanup
    }

end Materializer
