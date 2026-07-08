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

class IOPathTest extends UniTest:

  test("parse absolute Unix path") {
    val path = IOPath.parse("/home/user/file.txt")
    path.isAbsolute shouldBe true
    path.segments shouldBe Seq("home", "user", "file.txt")
    path.fileName shouldBe "file.txt"
    path.extension shouldBe "txt"
    path.baseName shouldBe "file"
  }

  test("parse relative path") {
    val path = IOPath.parse("foo/bar/baz.scala")
    path.isAbsolute shouldBe false
    path.segments shouldBe Seq("foo", "bar", "baz.scala")
    path.fileName shouldBe "baz.scala"
    path.extension shouldBe "scala"
  }

  test("parse Windows path") {
    val path = IOPath.parse("C:\\Users\\test\\file.txt")
    path.isAbsolute shouldBe true
    path.segments shouldBe Seq("C:", "Users", "test", "file.txt")
    path.fileName shouldBe "file.txt"
  }

  test("parse empty path") {
    val path = IOPath.parse("")
    path.isAbsolute shouldBe false
    path.segments shouldBe Seq.empty
    path.fileName shouldBe ""
  }

  test("get parent path") {
    val path = IOPath.parse("/home/user/file.txt")
    path.parent shouldBe Some(IOPath.parse("/home/user"))
    path.parent.flatMap(_.parent) shouldBe Some(IOPath.parse("/home"))
    path.parent.flatMap(_.parent).flatMap(_.parent) shouldBe Some(IOPath.parse("/"))
    path.parent.flatMap(_.parent).flatMap(_.parent).flatMap(_.parent) shouldBe None
  }

  test("resolve child path") {
    val base  = IOPath.parse("/home/user")
    val child = base.resolve("docs/file.txt")
    // `.path` renders every separator with the platform one, the leading segment included, so build
    // the expectation the same way — on Windows this is `\home\user\docs\file.txt`. `.posixPath` is
    // the forward-slash form.
    val sep = IOPath.separator
    child.path shouldBe s"${sep}home${sep}user${sep}docs${sep}file.txt"
    child.posixPath shouldBe "/home/user/docs/file.txt"
  }

  test("resolve absolute child returns child") {
    val base  = IOPath.parse("/home/user")
    val child = base.resolve("/etc/config")
    child.path shouldBe s"${IOPath.separator}etc${IOPath.separator}config"
  }

  test("use / operator for resolve") {
    val path = IOPath.parse("/home") / "user" / "file.txt"
    path.posixPath shouldBe "/home/user/file.txt"
  }

  test("normalize path with dots") {
    val path1 = IOPath.parse("/home/user/../user/./file.txt")
    path1.normalize.posixPath shouldBe "/home/user/file.txt"

    val path2 = IOPath.parse("foo/./bar/../baz")
    path2.normalize.posixPath shouldBe "foo/baz"
  }

  test("relativeTo") {
    val path = IOPath.parse("/home/user/docs/file.txt")
    val base = IOPath.parse("/home/user")
    path.relativeTo(base).posixPath shouldBe "docs/file.txt"
  }

  test("relativeTo with parent traversal") {
    val path = IOPath.parse("/home/user/docs/file.txt")
    val base = IOPath.parse("/home/user/other")
    path.relativeTo(base).posixPath shouldBe "../docs/file.txt"
  }

  test("startsWith") {
    val path = IOPath.parse("/home/user/docs/file.txt")
    path.startsWith(IOPath.parse("/home")) shouldBe true
    path.startsWith(IOPath.parse("/home/user")) shouldBe true
    path.startsWith(IOPath.parse("/etc")) shouldBe false
  }

  test("endsWith") {
    val path = IOPath.parse("/home/user/docs/file.txt")
    path.endsWith(IOPath.parse("file.txt")) shouldBe true
    path.endsWith(IOPath.parse("docs/file.txt")) shouldBe true
    path.endsWith(IOPath.parse("other.txt")) shouldBe false
  }

  test("root") {
    val unixPath = IOPath.parse("/home/user")
    unixPath.root.map(_.posixPath) shouldBe Some("/")

    val relativePath = IOPath.parse("foo/bar")
    relativePath.root shouldBe None
  }

  test("of factory method") {
    val path = IOPath.of("home", "user", "file.txt")
    path.posixPath shouldBe "home/user/file.txt"

    val absPath = IOPath.of("/home", "user", "file.txt")
    absPath.posixPath shouldBe "/home/user/file.txt"
  }

  test("file with no extension") {
    val path = IOPath.parse("/home/user/Makefile")
    path.fileName shouldBe "Makefile"
    path.extension shouldBe ""
    path.baseName shouldBe "Makefile"
  }

  test("file starting with dot") {
    val path = IOPath.parse("/home/user/.gitignore")
    path.fileName shouldBe ".gitignore"
    path.extension shouldBe "gitignore"
    path.baseName shouldBe ""
  }

  test("file with multiple dots") {
    val path = IOPath.parse("/home/user/file.test.scala")
    path.fileName shouldBe "file.test.scala"
    path.extension shouldBe "scala"
    path.baseName shouldBe "file.test"
  }

  test("segmentCount") {
    IOPath.parse("/home/user/file.txt").segmentCount shouldBe 3
    IOPath.parse("foo/bar").segmentCount shouldBe 2
    IOPath.parse("/").segmentCount shouldBe 0
  }

end IOPathTest
