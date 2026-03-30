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
package wvlet.uni.io

import wvlet.uni.test.UniTest

class IOFileSystemTest extends UniTest:
  FileSystemInit.init()

  test("IO should delegate file read/write operations") {
    val tmpFile = IO.createTempFile("io-test", ".txt", None)
    try
      IO.writeString(tmpFile, "hello from IO", WriteMode.Create)
      val content = IO.readString(tmpFile)
      content shouldBe "hello from IO"
    finally
      IO.delete(tmpFile)
  }

  test("IO should delegate exists and isFile") {
    val tmpFile = IO.createTempFile("io-test", ".txt", None)
    try
      IO.exists(tmpFile) shouldBe true
      IO.isFile(tmpFile) shouldBe true
      IO.isDirectory(tmpFile) shouldBe false
    finally
      IO.delete(tmpFile)
  }

  test("IO should delegate directory operations") {
    val tmpDir = IO.createTempDirectory("io-dir-test", None)
    try
      IO.exists(tmpDir) shouldBe true
      IO.isDirectory(tmpDir) shouldBe true
      IO.list(tmpDir, ListOptions.default).shouldBe(Seq.empty)
    finally
      IO.deleteRecursively(tmpDir)
  }

  test("IO should delegate readLines") {
    val tmpFile = IO.createTempFile("io-lines-test", ".txt", None)
    try
      IO.writeString(tmpFile, "line1\nline2\nline3", WriteMode.Create)
      val lines = IO.readLines(tmpFile)
      lines.size shouldBe 3
      lines.head shouldBe "line1"
    finally
      IO.delete(tmpFile)
  }

  test("IO should delegate copy and move") {
    val src = IO.createTempFile("io-copy-src", ".txt", None)
    val dst = IO.createTempFile("io-copy-dst", ".txt", None)
    try
      IO.writeString(src, "copy me", WriteMode.Create)
      IO.copy(src, dst, CopyOptions.default.withOverwrite(true))
      IO.readString(dst) shouldBe "copy me"
    finally
      IO.deleteIfExists(src)
      IO.deleteIfExists(dst)
  }

  test("IO should expose currentDirectory") {
    val cwd = IO.currentDirectory
    IO.exists(cwd) shouldBe true
    IO.isDirectory(cwd) shouldBe true
  }

end IOFileSystemTest
