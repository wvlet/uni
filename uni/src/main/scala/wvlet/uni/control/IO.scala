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
package wvlet.uni.control

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets

import wvlet.uni.control.Control.withResource
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath

/**
  */
object IO:
  // Ensure the platform-specific FileSystem implementation is registered before any
  // file-path-based read uses it. Without this, the first call to readAsString(String) on a
  // fresh JVM would throw IllegalStateException("FileSystem not initialized").
  FileSystemInit.init()

  /**
    * Reads the file at the given path string as a UTF-8 string. Backed by the cross-platform
    * [[wvlet.uni.io.FileSystem]], so it works on JVM, Node.js (Scala.js), and Scala Native.
    */
  def readAsString(filePath: String): String = FileSystem.readString(IOPath(filePath))

  def readAsString(f: File): String = readAsString(f.toURI.toURL)

  def readAsString(url: URL): String =
    withResource(url.openStream()) { in =>
      readAsString(in)
    }

  def readAsString(in: InputStream): String = new String(readFully(in), StandardCharsets.UTF_8)

  def readFully(in: InputStream): Array[Byte] =
    val byteArray =
      if in == null then
        Array.emptyByteArray
      else
        withResource(new ByteArrayOutputStream) { b =>
          val buf = new Array[Byte](8192)
          withResource(in) { src =>
            var readBytes = 0
            while {
              readBytes = src.read(buf);
              readBytes != -1
            } do
              b.write(buf, 0, readBytes)
          }
          b.toByteArray
        }
    byteArray

end IO
