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

class FileSystemTest extends UniTest:
  // Initialize the file system
  FileSystemInit.init()

  // Create test directory lazily
  private lazy val testDir: IOPath = FileSystem.createTempDirectory("fs-test")

  test("currentDirectory exists") {
    val cwd = FileSystem.currentDirectory
    FileSystem.exists(cwd) shouldBe true
    FileSystem.isDirectory(cwd) shouldBe true
  }

  test("homeDirectory exists") {
    val home = FileSystem.homeDirectory
    FileSystem.exists(home) shouldBe true
    FileSystem.isDirectory(home) shouldBe true
  }

  test("tempDirectory exists") {
    val tmp = FileSystem.tempDirectory
    FileSystem.exists(tmp) shouldBe true
    FileSystem.isDirectory(tmp) shouldBe true
  }

  test("write and read string") {
    val file    = testDir / "test-string.txt"
    val content = "Hello, World!\nLine 2"

    FileSystem.writeString(file, content)
    FileSystem.exists(file) shouldBe true
    FileSystem.isFile(file) shouldBe true

    val read = FileSystem.readString(file)
    read shouldBe content
  }

  test("write and read bytes") {
    val file    = testDir / "test-bytes.bin"
    val content = Array[Byte](0, 1, 2, 127, -128, -1)

    FileSystem.writeBytes(file, content)
    FileSystem.exists(file) shouldBe true

    val read = FileSystem.readBytes(file)
    read.toSeq shouldBe content.toSeq
  }

  test("read lines") {
    val file    = testDir / "test-lines.txt"
    val content = "Line 1\nLine 2\nLine 3"

    FileSystem.writeString(file, content)

    val lines = FileSystem.readLines(file)
    lines shouldBe Seq("Line 1", "Line 2", "Line 3")
  }

  test("append string") {
    val file = testDir / "test-append.txt"

    FileSystem.writeString(file, "First\n")
    FileSystem.appendString(file, "Second\n")
    FileSystem.appendString(file, "Third")

    val content = FileSystem.readString(file)
    content shouldBe "First\nSecond\nThird"
  }

  test("write with CreateNew fails if file exists") {
    val file = testDir / "test-create-new.txt"

    FileSystem.writeString(file, "original")

    intercept[FileAlreadyExistsException] {
      FileSystem.writeString(file, "new content", WriteMode.CreateNew)
    }

    // Original content should be unchanged
    FileSystem.readString(file) shouldBe "original"
  }

  test("create directory") {
    val dir = testDir / "subdir1" / "subdir2"
    FileSystem.createDirectory(dir)

    FileSystem.exists(dir) shouldBe true
    FileSystem.isDirectory(dir) shouldBe true
  }

  test("list directory") {
    val dir = testDir / "list-test"
    FileSystem.createDirectory(dir)

    FileSystem.writeString(dir / "file1.txt", "content1")
    FileSystem.writeString(dir / "file2.txt", "content2")
    FileSystem.writeString(dir / ".hidden", "hidden")
    FileSystem.createDirectory(dir / "subdir")

    val filesDefault = FileSystem.list(dir)
    filesDefault.map(_.fileName).toSet shouldBe Set("file1.txt", "file2.txt", "subdir")

    val filesWithHidden = FileSystem.list(dir, ListOptions(includeHidden = true))
    filesWithHidden.map(_.fileName).toSet shouldBe
      Set("file1.txt", "file2.txt", ".hidden", "subdir")

    val txtFiles = FileSystem.list(dir, ListOptions().withExtensions("txt"))
    txtFiles.map(_.fileName).toSet shouldBe Set("file1.txt", "file2.txt")
  }

  test("list directory recursively") {
    val dir = testDir / "recursive-test"
    FileSystem.createDirectory(dir / "a" / "b")
    FileSystem.writeString(dir / "root.txt", "root")
    FileSystem.writeString(dir / "a" / "file-a.txt", "a")
    FileSystem.writeString(dir / "a" / "b" / "file-b.txt", "b")

    val allFiles = FileSystem.list(dir, ListOptions(recursive = true))
    allFiles.map(_.fileName).toSet shouldBe Set("root.txt", "a", "file-a.txt", "b", "file-b.txt")
  }

  test("delete file") {
    val file = testDir / "to-delete.txt"
    FileSystem.writeString(file, "delete me")

    FileSystem.exists(file) shouldBe true
    FileSystem.delete(file) shouldBe true
    FileSystem.exists(file) shouldBe false
  }

  test("delete directory recursively") {
    val dir = testDir / "delete-recursive"
    FileSystem.createDirectory(dir / "sub")
    FileSystem.writeString(dir / "file.txt", "content")
    FileSystem.writeString(dir / "sub" / "nested.txt", "nested")

    FileSystem.exists(dir) shouldBe true
    FileSystem.deleteRecursively(dir) shouldBe true
    FileSystem.exists(dir) shouldBe false
  }

  test("copy file") {
    val source = testDir / "copy-source.txt"
    val target = testDir / "copy-target.txt"

    FileSystem.writeString(source, "copy content")
    FileSystem.copy(source, target)

    FileSystem.exists(target) shouldBe true
    FileSystem.readString(target) shouldBe "copy content"
    FileSystem.exists(source) shouldBe true // Source still exists
  }

  test("copy directory recursively") {
    val sourceDir = testDir / "copy-dir-source"
    val targetDir = testDir / "copy-dir-target"

    FileSystem.createDirectory(sourceDir / "sub")
    FileSystem.writeString(sourceDir / "file.txt", "root file")
    FileSystem.writeString(sourceDir / "sub" / "nested.txt", "nested file")

    FileSystem.copy(sourceDir, targetDir, CopyOptions(recursive = true))

    FileSystem.isDirectory(targetDir) shouldBe true
    FileSystem.readString(targetDir / "file.txt") shouldBe "root file"
    FileSystem.readString(targetDir / "sub" / "nested.txt") shouldBe "nested file"
  }

  test("move file") {
    val source = testDir / "move-source.txt"
    val target = testDir / "move-target.txt"

    FileSystem.writeString(source, "move content")
    FileSystem.move(source, target)

    FileSystem.exists(source) shouldBe false
    FileSystem.exists(target) shouldBe true
    FileSystem.readString(target) shouldBe "move content"
  }

  test("file info") {
    val file = testDir / "info-test.txt"
    FileSystem.writeString(file, "test content")

    val info = FileSystem.info(file)
    info.exists shouldBe true
    info.isFile shouldBe true
    info.isDirectory shouldBe false
    info.size shouldBe 12L // "test content" is 12 bytes
    info.fileName shouldBe "info-test.txt"
    info.isReadable shouldBe true
    info.isWritable shouldBe true
  }

  test("info for non-existent file") {
    val file = testDir / "does-not-exist.txt"
    val info = FileSystem.info(file)

    info.exists shouldBe false
    info.fileType shouldBe FileType.NotFound
  }

  test("create temp file") {
    val tempFile = FileSystem.createTempFile("test-", ".txt", Some(testDir))

    FileSystem.exists(tempFile) shouldBe true
    tempFile.extension shouldBe "txt"
    tempFile.fileName.startsWith("test-") shouldBe true
  }

  test("create temp directory") {
    val tempDir = FileSystem.createTempDirectory("test-dir-", Some(testDir))

    FileSystem.exists(tempDir) shouldBe true
    FileSystem.isDirectory(tempDir) shouldBe true
  }

  // ============================================================
  // Streaming I/O tests
  // ============================================================

  test("readLinesLazy returns lines lazily") {
    val file    = testDir / "lazy-lines.txt"
    val content = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
    FileSystem.writeString(file, content)

    val iter  = FileSystem.readLinesLazy(file)
    val lines = iter.toSeq
    lines shouldBe Seq("Line 1", "Line 2", "Line 3", "Line 4", "Line 5")
  }

  test("readLinesLazy on empty file") {
    val file = testDir / "lazy-lines-empty.txt"
    FileSystem.writeString(file, "")

    val iter = FileSystem.readLinesLazy(file)
    iter.hasNext shouldBe false
  }

  test("readChunks returns byte chunks") {
    val file = testDir / "chunks.bin"
    val data = new Array[Byte](20000)
    java.util.Arrays.fill(data, 42.toByte)
    FileSystem.writeBytes(file, data)

    val chunks    = FileSystem.readChunks(file).toSeq
    val totalSize = chunks.map(_.length).sum
    totalSize shouldBe 20000
    // Default chunk size 8192: 8192 + 8192 + 3616
    chunks.length shouldBe 3
    chunks.head.length shouldBe 8192
  }

  test("readChunks with custom chunk size") {
    val file = testDir / "chunks-custom.bin"
    val data = new Array[Byte](100)
    java.util.Arrays.fill(data, 1.toByte)
    FileSystem.writeBytes(file, data)

    val chunks = FileSystem.readChunks(file, chunkSize = 30).toSeq
    chunks.length shouldBe 4
    chunks.last.length shouldBe 10
    chunks.map(_.length).sum shouldBe 100
  }

  test("readChunks on empty file") {
    val file = testDir / "chunks-empty.bin"
    FileSystem.writeString(file, "")

    val chunks = FileSystem.readChunks(file).toSeq
    chunks.isEmpty shouldBe true
  }

  test("readStream returns InputStream") {
    val file    = testDir / "stream-read.txt"
    val content = "stream content"
    FileSystem.writeString(file, content)

    val in = FileSystem.readStream(file)
    try
      val bytes  = new Array[Byte](1024)
      val read   = in.read(bytes)
      val result = new String(bytes, 0, read, "UTF-8")
      result shouldBe content
    finally
      in.close()
  }

  test("writeStream returns OutputStream") {
    val file    = testDir / "stream-write.txt"
    val content = "written via stream"

    val out = FileSystem.writeStream(file)
    try out.write(content.getBytes("UTF-8"))
    finally out.close()

    FileSystem.readString(file) shouldBe content
  }

  test("writeStream with append mode") {
    val file = testDir / "stream-append.txt"
    FileSystem.writeString(file, "first")

    val out = FileSystem.writeStream(file, WriteMode.Append)
    try out.write("-second".getBytes("UTF-8"))
    finally out.close()

    FileSystem.readString(file) shouldBe "first-second"
  }

  test("readLinesRx emits lines") {
    val file = testDir / "rx-lines.txt"
    FileSystem.writeString(file, "A\nB\nC")

    val lines = scala.collection.mutable.ArrayBuffer[String]()
    FileSystem.readLinesRx(file).run(lines += _)
    lines.toSeq shouldBe Seq("A", "B", "C")
  }

  test("readChunksRx emits chunks") {
    val file = testDir / "rx-chunks.bin"
    val data = new Array[Byte](100)
    java.util.Arrays.fill(data, 7.toByte)
    FileSystem.writeBytes(file, data)

    val chunks = scala.collection.mutable.ArrayBuffer[Array[Byte]]()
    FileSystem.readChunksRx(file, chunkSize = 40).run(chunks += _)
    chunks.length shouldBe 3
    chunks.map(_.length).sum shouldBe 100
  }

end FileSystemTest
