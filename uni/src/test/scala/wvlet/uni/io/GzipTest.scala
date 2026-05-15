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

import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest

class GzipTest extends UniTest:
  FileSystemInit.init()
  private lazy val tempDir = FileSystem.createTempDirectory("gzip-test")

  test("compress and decompress bytes") {
    val original   = "Hello, World! This is a test message for gzip compression.".getBytes("UTF-8")
    val compressed = Gzip.compress(original)
    val decompressed = Gzip.decompress(compressed)

    decompressed shouldBe original
    // Compressed should be different (and typically smaller for larger data)
    compressed shouldNotBe original
  }

  test("compress and decompress empty data") {
    val original     = Array.emptyByteArray
    val compressed   = Gzip.compress(original)
    val decompressed = Gzip.decompress(compressed)

    decompressed shouldBe original
  }

  test("compress and decompress large data") {
    val original     = ("A" * 10000).getBytes("UTF-8")
    val compressed   = Gzip.compress(original)
    val decompressed = Gzip.decompress(compressed)

    decompressed shouldBe original
    // Large repetitive data should compress well
    (compressed.length < original.length) shouldBe true
  }

  test("compressFile and decompressFile") {
    val originalPath   = tempDir / "original.txt"
    val compressedPath = tempDir / "original.txt.gz"
    val recoveredPath  = tempDir / "recovered.txt"

    val content = "File content for compression test.\nLine 2.\nLine 3."
    FileSystem.writeString(originalPath, content)

    Gzip.compressFile(originalPath, compressedPath)
    FileSystem.exists(compressedPath) shouldBe true

    Gzip.decompressFile(compressedPath, recoveredPath)
    FileSystem.exists(recoveredPath) shouldBe true

    val recovered = FileSystem.readString(recoveredPath)
    recovered shouldBe content
  }

  test("compressFile and decompressFile with String paths") {
    val originalPath   = (tempDir / "string-original.txt").posixPath
    val compressedPath = (tempDir / "string-original.txt.gz").posixPath
    val recoveredPath  = (tempDir / "string-recovered.txt").posixPath

    val content = "String-path round-trip."
    FileSystem.writeString(originalPath, content)

    Gzip.compressFile(originalPath, compressedPath)
    FileSystem.exists(compressedPath) shouldBe true

    Gzip.decompressFile(compressedPath, recoveredPath)
    FileSystem.readString(recoveredPath) shouldBe content
  }

end GzipTest
