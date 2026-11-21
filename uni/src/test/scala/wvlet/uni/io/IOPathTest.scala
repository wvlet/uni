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

  test("Windows drive root keeps its separator") {
    val root = IOPath.parse("C:\\")
    root.segments shouldBe Seq("C:")
    root.posixPath shouldBe "C:/"
    // `C:` alone is the current folder on drive C, so a drive root must render as `C:\`
    // Touch FileSystem first: it sets IOPath.isWindows and separator when it initializes
    if !FileSystem.isBrowser && IOPath.isWindows then
      root.path shouldBe "C:\\"
      IOPath.parse("C:/Users").path shouldBe "C:\\Users"
  }

  test("Windows drive root as parent, root, and normalized path") {
    val file = IOPath.parse("C:\\Users\\file.txt")
    file.parent.flatMap(_.parent) shouldBe Some(IOPath.parse("C:\\"))
    file.parent.flatMap(_.parent).flatMap(_.parent) shouldBe None
    file.root shouldBe Some(IOPath.parse("C:\\"))
    file.root.map(_.posixPath) shouldBe Some("C:/")
    IOPath.parse("C:\\foo").parent.map(_.posixPath) shouldBe Some("C:/")
    IOPath.parse("C:\\foo\\..\\..").normalize.posixPath shouldBe "C:/"
    (IOPath.parse("C:\\") / "foo").posixPath shouldBe "C:/foo"
  }

  test("Windows extended-length drive path drops its \\\\?\\ prefix") {
    val file = IOPath.parse("\\\\?\\C:\\data\\x.parquet")
    file shouldBe IOPath.parse("C:\\data\\x.parquet")
    file.posixPath shouldBe "C:/data/x.parquet"
    file.parent.map(_.posixPath) shouldBe Some("C:/data")
    file.parent.flatMap(_.parent) shouldBe Some(IOPath.parse("C:\\"))
    file.root shouldBe Some(IOPath.parse("C:\\"))
    IOPath.parse("\\\\?\\C:\\data\\..\\..\\x").normalize.posixPath shouldBe "C:/x"
    (IOPath.parse("\\\\?\\C:\\") / "data").posixPath shouldBe "C:/data"
    (IOPath.parse("C:\\") / "\\\\?\\D:\\y").posixPath shouldBe "D:/y"
    file.relativeTo(IOPath.parse("C:\\")) shouldBe IOPath.parse("data/x.parquet")
    file.startsWith(IOPath.parse("C:\\data")) shouldBe true
    IOPath.parse("\\\\.\\C:\\data") shouldBe IOPath.parse("C:\\data")
    if !FileSystem.isBrowser && IOPath.isWindows then
      file.path shouldBe "C:\\data\\x.parquet"
      IOPath.parse("\\\\?\\C:\\").path shouldBe "C:\\"
  }

  test("Windows extended-length UNC path parses as the UNC path") {
    IOPath.parse("\\\\?\\UNC\\server\\share\\dir") shouldBe IOPath.parse("\\\\server\\share\\dir")
    IOPath.parse("\\\\?\\unc\\server\\share") shouldBe IOPath.parse("\\\\server\\share")
    IOPath.parse("\\\\.\\UNC\\server\\share") shouldBe IOPath.parse("\\\\server\\share")
  }

  test("Windows device paths are rejected") {
    Seq("\\\\.\\pipe\\name", "\\\\.\\COM1", "\\\\?\\Volume{1234}\\x", "\\\\.\\C:", "\\\\?\\UNC\\")
      .foreach { p =>
        val e = intercept[IllegalArgumentException] {
          IOPath.parse(p)
        }
        e.getMessage shouldContain p
      }
  }

  test("forward-slash //?/ and //./ stay POSIX paths") {
    IOPath.parse("//?/x").posixPath shouldBe "/?/x"
    IOPath.parse("//./x").normalize.posixPath shouldBe "/x"
  }

  test("Windows drive-relative path is relative and keeps its drive") {
    val p = IOPath.parse("C:foo\\bar")
    p.isAbsolute shouldBe false
    p.segments shouldBe Seq("C:", "foo", "bar")
    p.posixPath shouldBe "C:foo/bar"
    p.fileName shouldBe "bar"
    p.root shouldBe None
    p.parent.map(_.posixPath) shouldBe Some("C:foo")
    p.parent.flatMap(_.parent).map(_.posixPath) shouldBe Some("C:")
    p.parent.flatMap(_.parent).flatMap(_.parent) shouldBe None

    // `C:` alone is the current folder on drive C, not its root
    val drive = IOPath.parse("C:")
    drive.isAbsolute shouldBe false
    drive.segments shouldBe Seq("C:")
    drive.posixPath shouldBe "C:"
    IOPath.parse("C:foo").fileName shouldBe "foo"
    // A relative path starting with a drive segment must not render as the absolute `C:\foo`
    IOPath(Seq("C:", "foo"), isAbsolute = false).posixPath shouldBe "C:foo"
    if !FileSystem.isBrowser && IOPath.isWindows then
      p.path shouldBe "C:foo\\bar"
      drive.path shouldBe "C:"
  }

  test("resolve a Windows drive-relative path") {
    (IOPath.parse("C:foo") / "bar").posixPath shouldBe "C:foo/bar"
    (IOPath.parse("C:") / "foo").posixPath shouldBe "C:foo"
    IOPath.of("C:", "foo").posixPath shouldBe "C:foo"
    // On the same drive the rest continues the base path, as java.nio and Python resolve it
    (IOPath.parse("C:\\work") / "C:foo").posixPath shouldBe "C:/work/foo"
    (IOPath.parse("c:\\work") / IOPath.parse("C:foo")).posixPath shouldBe "c:/work/foo"
    (IOPath.parse("C:bar") / "C:foo").posixPath shouldBe "C:bar/foo"
    // On another drive or a path without a drive, it names its own location
    (IOPath.parse("D:\\work") / "C:foo").posixPath shouldBe "C:foo"
    (IOPath.parse("/work") / "C:foo").posixPath shouldBe "C:foo"
    (IOPath.parse("work") / "C:foo").posixPath shouldBe "C:foo"
    (IOPath.parse("C:foo") / "D:\\bar").posixPath shouldBe "D:/bar"
  }

  test("relativeTo, normalize, and startsWith on a Windows drive-relative path") {
    IOPath.parse("C:foo\\bar").relativeTo(IOPath.parse("C:foo")).posixPath shouldBe "bar"
    IOPath.parse("C:foo").relativeTo(IOPath.parse("C:")).posixPath shouldBe "foo"
    IOPath.parse("C:").relativeTo(IOPath.parse("C:foo")).posixPath shouldBe ".."
    IOPath.parse("C:foo").relativeTo(IOPath.parse("D:foo")).posixPath shouldBe "C:foo"
    IOPath.parse("C:foo").relativeTo(IOPath.parse("bar")).posixPath shouldBe "C:foo"
    IOPath.parse("bar").relativeTo(IOPath.parse("C:foo")).posixPath shouldBe "bar"

    IOPath.parse("C:foo\\..\\bar").normalize.posixPath shouldBe "C:bar"
    IOPath.parse("C:foo\\..").normalize.posixPath shouldBe "C:"
    IOPath.parse("C:.\\foo").normalize.posixPath shouldBe "C:foo"
    // `..` right after the drive goes above the drive's current folder, so it stays
    IOPath.parse("C:..\\foo").normalize.posixPath shouldBe "C:../foo"
    IOPath.parse("C:foo\\..\\..").normalize.posixPath shouldBe "C:.."

    IOPath.parse("C:foo\\bar").startsWith(IOPath.parse("C:foo")) shouldBe true
    IOPath.parse("C:foo").startsWith(IOPath.parse("C:")) shouldBe true
    IOPath.parse("C:foo").startsWith(IOPath.parse("C:\\")) shouldBe false
    IOPath.parse("C:\\foo").startsWith(IOPath.parse("C:")) shouldBe false
    IOPath.parse("C:foo").startsWith(IOPath.parse("foo")) shouldBe false
  }

  test("colon in a Unix path is not a drive") {
    IOPath.parse("/ab:c/d").posixPath shouldBe "/ab:c/d"
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
