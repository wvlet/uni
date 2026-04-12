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

class PermissionsTest extends UniTest:
  FileSystemInit.init()

  private lazy val testDir: IOPath = FileSystem.createTempDirectory("perm-test")

  test("get file permissions") {
    val file = testDir / "test-perms.txt"
    FileSystem.writeString(file, "hello")

    val perms = FileSystem.permissions(file)
    // File should at least be readable and writable by owner
    perms.ownerRead shouldBe true
    perms.ownerWrite shouldBe true
  }

  test("set file permissions") {
    val file = testDir / "test-set-perms.txt"
    FileSystem.writeString(file, "hello")

    FileSystem.setPermissions(file, PermSet("rw-r--r--"))
    val perms = FileSystem.permissions(file)
    perms.toPermString shouldBe "rw-r--r--"

    FileSystem.setPermissions(file, PermSet("rwxr-xr-x"))
    val perms2 = FileSystem.permissions(file)
    perms2.toPermString shouldBe "rwxr-xr-x"
  }

  test("set permissions to read-only") {
    val file = testDir / "test-readonly.txt"
    FileSystem.writeString(file, "hello")

    FileSystem.setPermissions(file, PermSet("r--r--r--"))
    val perms = FileSystem.permissions(file)
    perms.ownerRead shouldBe true
    perms.ownerWrite shouldBe false
    perms.ownerExecute shouldBe false

    // Restore write permission for cleanup
    FileSystem.setPermissions(file, PermSet("rw-r--r--"))
  }

  test("directory permissions") {
    val dir = testDir / "test-dir-perms"
    FileSystem.createDirectory(dir)

    val perms = FileSystem.permissions(dir)
    perms.ownerRead shouldBe true
    perms.ownerExecute shouldBe true // Directories need execute for traversal

    FileSystem.setPermissions(dir, PermSet("rwxr-x---"))
    val perms2 = FileSystem.permissions(dir)
    perms2.toPermString shouldBe "rwxr-x---"
  }

  test("permissions via FileInfo") {
    val file = testDir / "test-info-perms.txt"
    FileSystem.writeString(file, "hello")
    FileSystem.setPermissions(file, PermSet("rw-r--r--"))

    val info = FileSystem.info(file)
    info.permissions shouldBe Some(PermSet("rw-r--r--"))
  }

  test("get file owner") {
    val file = testDir / "test-owner.txt"
    FileSystem.writeString(file, "hello")

    val owner = FileSystem.owner(file)
    owner.nonEmpty shouldBe true
  }

  test("get file group") {
    val file = testDir / "test-group.txt"
    FileSystem.writeString(file, "hello")

    val group = FileSystem.group(file)
    group.nonEmpty shouldBe true
  }

  test("owner and group via FileInfo") {
    val file = testDir / "test-info-owner.txt"
    FileSystem.writeString(file, "hello")

    val info = FileSystem.info(file)
    info.owner.nonEmpty shouldBe true
    info.group.nonEmpty shouldBe true
  }

  test("permissions via IO facade") {
    val file = testDir / "test-io-perms.txt"
    FileSystem.writeString(file, "hello")

    IO.setPermissions(file, PermSet("rwxr-xr-x"))
    IO.permissions(file).toPermString shouldBe "rwxr-xr-x"
  }

end PermissionsTest
