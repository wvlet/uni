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

  test("IO.path should parse a path string") {
    val p = IO.path("/home/user/file.txt")
    p.isAbsolute shouldBe true
    p.segments shouldBe Seq("home", "user", "file.txt")
    p.fileName shouldBe "file.txt"
  }

  test("IO.path should join segments") {
    val p = IO.path("home", "user", "file.txt")
    p.isAbsolute shouldBe false
    p.segments shouldBe Seq("home", "user", "file.txt")
  }

  test("IO.path should compose with IO operations") {
    val tmpDir  = IO.createTempDirectory("io-path-test", None)
    val tmpFile = IO.path(tmpDir.posixPath, "nested.txt")
    try
      IO.writeString(tmpFile, "io.path works", WriteMode.Create)
      IO.readString(tmpFile) shouldBe "io.path works"
    finally
      IO.deleteRecursively(tmpDir)
  }

  test("IO should accept plain String paths for read/write") {
    val tmpDir   = IO.createTempDirectory("io-string-test", None)
    val filePath = (tmpDir / "string-path.txt").posixPath
    try
      IO.writeString(filePath, "hello string path")
      IO.readString(filePath) shouldBe "hello string path"
      IO.exists(filePath) shouldBe true
      IO.isFile(filePath) shouldBe true
    finally
      IO.deleteRecursively(tmpDir)
  }

  test("IO should accept plain String paths for write modes") {
    val tmpDir   = IO.createTempDirectory("io-string-modes", None)
    val filePath = (tmpDir / "modes.txt").posixPath
    try
      IO.writeString(filePath, "first", WriteMode.Create)
      IO.appendString(filePath, "-second")
      IO.readString(filePath) shouldBe "first-second"
    finally
      IO.deleteRecursively(tmpDir)
  }

  test("IO should accept plain String paths for listing and delete") {
    val tmpDir = IO.createTempDirectory("io-string-list", None)
    val a      = (tmpDir / "a.txt").posixPath
    val b      = (tmpDir / "b.txt").posixPath
    try
      IO.writeString(a, "a")
      IO.writeString(b, "b")
      val names = IO.list(tmpDir.posixPath).map(_.fileName).toSet
      names shouldBe Set("a.txt", "b.txt")
      IO.delete(a) shouldBe true
      IO.exists(a) shouldBe false
    finally
      IO.deleteRecursively(tmpDir)
  }

  test("IO should accept plain String paths for copy and move") {
    val tmpDir = IO.createTempDirectory("io-string-copy", None)
    try
      val src = (tmpDir / "src.txt").posixPath
      val dst = (tmpDir / "dst.txt").posixPath
      val mv  = (tmpDir / "moved.txt").posixPath
      IO.writeString(src, "data")
      IO.copy(src, dst)
      IO.readString(dst) shouldBe "data"
      IO.move(dst, mv)
      IO.exists(dst) shouldBe false
      IO.readString(mv) shouldBe "data"
    finally
      IO.deleteRecursively(tmpDir)
  }

end IOFileSystemTest
