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
package wvlet.uni.sbt

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import scala.util.Using

/** Locates uni-ui.css on a classpath for the `uniUiCss` task. */
object UniUiCss:
  /** Where the uni-ui artifact keeps its stylesheet */
  val resourcePath: String = "wvlet/uni/ui/uni-ui.css"

  /** The content of the first uni-ui.css found in the classpath entries (directories or jars) */
  def find(classpath: Seq[File]): Option[Array[Byte]] = classpath
    .iterator
    .flatMap(read)
    .nextOption()

  private def read(entry: File): Option[Array[Byte]] =
    if entry.isDirectory then
      val f = File(entry, resourcePath)
      Option.when(f.isFile)(Files.readAllBytes(f.toPath))
    else if entry.isFile && entry.getName.endsWith(".jar") then
      Using.resource(ZipFile(entry)) { zip =>
        Option(zip.getEntry(resourcePath)).map(e =>
          Using.resource(zip.getInputStream(e))(_.readAllBytes())
        )
      }
    else
      None

end UniUiCss
