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

class ZipTest extends UniTest:
  FileSystemInit.init()
  private lazy val tempDir = FileSystem.createTempDirectory("zip-test")

  test("create and extract single file") {
    val sourceDir = tempDir / "single-src"
    FileSystem.createDirectory(sourceDir)
    FileSystem.writeString(sourceDir / "hello.txt", "Hello, World!")

    val archivePath = tempDir / "single.zip"
    Zip.create(archivePath, Seq(sourceDir / "hello.txt"))
    FileSystem.exists(archivePath) shouldBe true

    val extractDir = tempDir / "single-out"
    Zip.extract(archivePath, extractDir)
    FileSystem.readString(extractDir / "hello.txt") shouldBe "Hello, World!"
  }

  test("create and extract multiple files") {
    val sourceDir = tempDir / "multi-src"
    FileSystem.createDirectory(sourceDir)
    FileSystem.writeString(sourceDir / "a.txt", "File A content")
    FileSystem.writeString(sourceDir / "b.txt", "File B content")

    val archivePath = tempDir / "multi.zip"
    Zip.create(archivePath, Seq(sourceDir / "a.txt", sourceDir / "b.txt"))

    val extractDir = tempDir / "multi-out"
    Zip.extract(archivePath, extractDir)
    FileSystem.readString(extractDir / "a.txt") shouldBe "File A content"
    FileSystem.readString(extractDir / "b.txt") shouldBe "File B content"
  }

  test("create and extract nested directories") {
    val sourceDir = tempDir / "nested-src"
    FileSystem.createDirectory(sourceDir / "subdir")
    FileSystem.writeString(sourceDir / "root.txt", "Root file")
    FileSystem.writeString(sourceDir / "subdir" / "nested.txt", "Nested file")

    val archivePath = tempDir / "nested.zip"
    Zip.create(archivePath, Seq(sourceDir))

    val extractDir = tempDir / "nested-out"
    Zip.extract(archivePath, extractDir)
    FileSystem.readString(extractDir / "nested-src" / "root.txt") shouldBe "Root file"
    FileSystem.readString(extractDir / "nested-src" / "subdir" / "nested.txt") shouldBe
      "Nested file"
  }

  test("list entries") {
    val sourceDir = tempDir / "list-src"
    FileSystem.createDirectory(sourceDir / "sub")
    FileSystem.writeString(sourceDir / "file.txt", "content")
    FileSystem.writeString(sourceDir / "sub" / "nested.txt", "nested content")

    val archivePath = tempDir / "list.zip"
    Zip.create(archivePath, Seq(sourceDir))

    val entries = Zip.list(archivePath)
    val names   = entries.map(_.name).toSet
    names shouldContain "list-src/"
    names shouldContain "list-src/file.txt"
    names shouldContain "list-src/sub/"
    names shouldContain "list-src/sub/nested.txt"

    val fileEntry = entries.find(_.name == "list-src/file.txt").get
    fileEntry.isDirectory shouldBe false
    fileEntry.size shouldBe 7L // "content".length

    val dirEntry = entries.find(_.name == "list-src/").get
    dirEntry.isDirectory shouldBe true
  }

  test("handle empty archive") {
    val archivePath = tempDir / "empty.zip"
    Zip.create(archivePath, Seq.empty)
    FileSystem.exists(archivePath) shouldBe true

    val entries = Zip.list(archivePath)
    entries.isEmpty shouldBe true

    val extractDir = tempDir / "empty-out"
    Zip.extract(archivePath, extractDir)
    FileSystem.exists(extractDir) shouldBe true
  }

  test("handle empty file content") {
    val sourceDir = tempDir / "emptyfile-src"
    FileSystem.createDirectory(sourceDir)
    FileSystem.writeBytes(sourceDir / "empty.txt", Array.emptyByteArray)

    val archivePath = tempDir / "emptyfile.zip"
    Zip.create(archivePath, Seq(sourceDir / "empty.txt"))

    val entries = Zip.list(archivePath)
    val entry   = entries.find(_.name == "empty.txt").get
    entry.size shouldBe 0L

    val extractDir = tempDir / "emptyfile-out"
    Zip.extract(archivePath, extractDir)
    FileSystem.readBytes(extractDir / "empty.txt") shouldBe Array.emptyByteArray
  }

  test("round-trip preserves file content") {
    val sourceDir = tempDir / "roundtrip-src"
    FileSystem.createDirectory(sourceDir)

    // Test various content types
    FileSystem.writeString(sourceDir / "text.txt", "Hello\nWorld\n")
    FileSystem.writeBytes(sourceDir / "binary.bin", Array[Byte](0, 1, 2, 127, -128, -1))
    FileSystem.writeString(sourceDir / "large.txt", "A" * 10000)

    val archivePath = tempDir / "roundtrip.zip"
    Zip.create(
      archivePath,
      Seq(sourceDir / "text.txt", sourceDir / "binary.bin", sourceDir / "large.txt")
    )

    val extractDir = tempDir / "roundtrip-out"
    Zip.extract(archivePath, extractDir)

    FileSystem.readString(extractDir / "text.txt") shouldBe "Hello\nWorld\n"
    FileSystem.readBytes(extractDir / "binary.bin") shouldBe Array[Byte](0, 1, 2, 127, -128, -1)
    FileSystem.readString(extractDir / "large.txt") shouldBe ("A" * 10000)
  }

end ZipTest
