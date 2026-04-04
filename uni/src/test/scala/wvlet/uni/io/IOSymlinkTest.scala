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

class IOSymlinkTest extends UniTest:
  FileSystemInit.init()

  private lazy val testDir: IOPath = FileSystem.createTempDirectory("symlink-test")

  test("createSymlink and readSymlink for a file") {
    val target = testDir / "target.txt"
    val link   = testDir / "link.txt"

    FileSystem.writeString(target, "hello symlink")
    FileSystem.createSymlink(link, target)

    FileSystem.exists(link) shouldBe true
    FileSystem.readString(link) shouldBe "hello symlink"
    FileSystem.readSymlink(link) shouldBe target
  }

  test("info reports SymbolicLink type") {
    val target = testDir / "info-target.txt"
    val link   = testDir / "info-link.txt"

    FileSystem.writeString(target, "data")
    FileSystem.createSymlink(link, target)

    val fi = FileSystem.info(link)
    fi.fileType shouldBe FileType.SymbolicLink
  }

  test("createSymlink for a directory") {
    val targetDir = testDir / "target-dir"
    val linkDir   = testDir / "link-dir"

    FileSystem.createDirectory(targetDir)
    FileSystem.writeString(targetDir / "file.txt", "content")
    FileSystem.createSymlink(linkDir, targetDir)

    FileSystem.exists(linkDir) shouldBe true
    FileSystem.isDirectory(linkDir) shouldBe true
    FileSystem.readSymlink(linkDir) shouldBe targetDir
  }

end IOSymlinkTest
