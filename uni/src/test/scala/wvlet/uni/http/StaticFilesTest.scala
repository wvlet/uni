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
package wvlet.uni.http

import wvlet.uni.io.{FileSystem, IOPath}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

class StaticFilesTest extends UniTest:

  // site/ holds the served files; secret.txt sits next to it, outside the root
  private lazy val base: IOPath =
    val dir  = FileSystem.createTempDirectory("static-files-test")
    val site = dir / "site"
    FileSystem.createDirectory(site)
    FileSystem.createDirectory(site / "assets")
    FileSystem.createDirectory(site / "docs")
    FileSystem.createDirectory(site / ".git")
    FileSystem.createDirectory(site / ".well-known")
    FileSystem.writeString(site / "index.html", "<h1>home</h1>")
    FileSystem.writeString(site / "assets" / "app.js", "console.log(1)")
    FileSystem.writeString(site / "assets" / "app.CSS", "body{}")
    FileSystem.writeString(site / "assets" / "data.bin", "bin")
    FileSystem.writeString(site / "assets" / "a b é.txt", "spaced")
    FileSystem.writeString(site / "assets" / "digits.txt", "0123456789")
    FileSystem.writeString(site / "assets" / "empty.txt", "")
    FileSystem.writeString(site / "docs" / "index.html", "<h1>docs</h1>")
    FileSystem.writeString(site / ".env", "TOKEN=1")
    FileSystem.writeString(site / ".git" / "config", "[core]")
    FileSystem.writeString(site / ".well-known" / "security.txt", "Contact: x")
    FileSystem.writeString(dir / "secret.txt", "outside")
    dir

  private def site: IOPath            = base / "site"
  private def files: StaticFiles      = StaticFiles(site.path)
  private def body(path: String)      = files.serve(path).flatMap(_.contentAsString)
  private val notFound: RxHttpHandler = RxHttpHandler.notFound

  private def symlinkSupported: Boolean = !IOPath.isWindows

  test("serve a file with its content type and nosniff") {
    val r = files.serve("/assets/app.js")
    r.flatMap(_.contentAsString) shouldBe Some("console.log(1)")
    r.flatMap(_.contentType).map(_.value) shouldBe Some("text/javascript; charset=utf-8")
    r.flatMap(_.header("X-Content-Type-Options")) shouldBe Some("nosniff")
  }

  test("pick the content type by extension, ignoring case") {
    files.serve("/assets/app.CSS").flatMap(_.contentType).map(_.fullType) shouldBe Some("text/css")
    files.serve("/assets/data.bin").flatMap(_.contentType) shouldBe
      Some(ContentType.ApplicationOctetStream)
    files
      .withContentType("bin", ContentType("application/x-test"))
      .serve("/assets/data.bin")
      .flatMap(_.contentType)
      .map(_.value) shouldBe Some("application/x-test")
  }

  test("serve the index file for the root and for directories with a trailing slash") {
    body("/") shouldBe Some("<h1>home</h1>")
    body("/docs/") shouldBe Some("<h1>docs</h1>")
    // Without the slash, relative URLs in the index would resolve against the parent
    body("/docs") shouldBe None
  }

  test("decode percent-escaped names") {
    body("/assets/a%20b%20%C3%A9.txt") shouldBe Some("spaced")
    body("/assets/a b é.txt") shouldBe Some("spaced")
    body("/assets/app%2ejs") shouldBe Some("console.log(1)")
  }

  test("reject path traversal in every encoding") {
    val attempts = Seq(
      "/../secret.txt",
      "/assets/../../secret.txt",
      "/%2e%2e/secret.txt",
      "/%2E%2E/secret.txt",
      "/..%2fsecret.txt",
      "/..%2Fsecret.txt",
      "/..%5csecret.txt",
      "/..\\secret.txt",
      "/assets/./app.js",
      "//secret.txt",
      "/assets//app.js",
      "/assets/app.js%00",
      "/assets/app.js%00.html",
      "/C:/secret.txt",
      "/assets/app.js::$DATA",
      "/%",
      "/%zz",
      "/%2",
      // Windows: names Paths.get rejects, names Win32 aliases, and 8.3 short names
      "/index.html.",
      "/index.html%20",
      "/a%3Cb",
      "/a%2A",
      "/x%3F",
      "/a%7Cb",
      "/GIT~1/config",
      "/ENV~1",
      "secret.txt",
      ""
    )
    attempts.foreach { p =>
      // map to the path so a failure names it
      files.serve(p).map(_ => p) shouldBe None
    }
  }

  test("reject an absolute path that escapes to the file system root") {
    files.serve(s"/${(base / "secret.txt").posixPath}") shouldBe None
  }

  test("hide dotfiles but serve .well-known") {
    body("/.env") shouldBe None
    body("/.git/config") shouldBe None
    body("/%2eenv") shouldBe None
    body("/.well-known/security.txt") shouldBe Some("Contact: x")
    files.withDotfiles(true).serve("/.env").flatMap(_.contentAsString) shouldBe Some("TOKEN=1")
  }

  test("never list a directory without an index file") {
    body("/assets/") shouldBe None
    body("/assets") shouldBe None
  }

  test("refuse symbolic links below the root") {
    if !symlinkSupported then
      skip("Symlinks are not supported on Scala Native + Windows")
    FileSystem.createSymlink(site / "leak.txt", base / "secret.txt")
    FileSystem.createSymlink(site / "linked", base)
    FileSystem.createSymlink(site / "app-link.js", site / "assets" / "app.js")
    body("/leak.txt") shouldBe None
    body("/linked/secret.txt") shouldBe None
    // Even a link that stays inside the root is refused
    body("/app-link.js") shouldBe None
  }

  test("allow the root itself to be a symbolic link") {
    if !symlinkSupported then
      skip("Symlinks are not supported on Scala Native + Windows")
    val link = base / "site-link"
    FileSystem.createSymlink(link, site)
    StaticFiles(link.path).serve("/assets/app.js").flatMap(_.contentAsString) shouldBe
      Some("console.log(1)")
  }

  test("treat an unreadable file as a miss") {
    if IOPath.isWindows then
      skip("POSIX permissions are not available on Windows")
    val locked = site / "locked.txt"
    FileSystem.writeString(locked, "locked")
    FileSystem.setPermissions(locked, wvlet.uni.io.PermSet.empty)
    try
      // The root user reads it anyway, so only assert when the permission takes effect
      if !FileSystem.info(locked).isReadable then
        body("/locked.txt") shouldBe None
    finally FileSystem.setPermissions(locked, wvlet.uni.io.PermSet("rw-------"))
  }

  test("treat a missing root as an empty directory") {
    StaticFiles((base / "not-built").path).serve("/index.html") shouldBe None
  }

  test("reject an index file name with a path") {
    intercept[IllegalArgumentException] {
      StaticFiles(site.path).withIndexFile("../secret.txt")
    }
  }

  test("pass non-GET requests and misses to the next handler") {
    val api = RxHttpHandler(_ => Rx.single(Response.ok("api")))
    for
      post <- files(Request.post("/index.html"), api)
      miss <- files(Request.get("/api/call"), api)
    yield
      post.contentAsString shouldBe Some("api")
      miss.contentAsString shouldBe Some("api")
  }

  test("fall back to the index file for HTML navigations the next handler misses") {
    val spa     = files.withSpaFallback(true)
    val htmlGet = Request.get("/chats/42").addHeader(HttpHeader.Accept, "text/html,*/*")
    for
      page   <- spa(htmlGet, notFound)
      noHtml <- spa(Request.get("/chats/42"), notFound)
      off    <- files(htmlGet, notFound)
      found  <- spa(htmlGet, RxHttpHandler(_ => Rx.single(Response.ok("route"))))
    yield
      page.contentAsString shouldBe Some("<h1>home</h1>")
      noHtml.status shouldBe HttpStatus.NotFound_404
      off.status shouldBe HttpStatus.NotFound_404
      found.contentAsString shouldBe Some("route")
  }

  private def etagOf(path: String): String =
    files.serve(path).flatMap(_.header(HttpHeader.ETag)).get

  private def conditional(path: String, ifNoneMatch: String): Option[Response] = files.serve(
    Request.get(path).addHeader(HttpHeader.IfNoneMatch, ifNoneMatch)
  )

  test("send a strong ETag and Cache-Control: no-cache") {
    val r = files.serve("/assets/app.js")
    r.flatMap(_.header(HttpHeader.ETag)).get shouldMatch { case s"\"${_}-${_}\"" =>
    }
    r.flatMap(_.header(HttpHeader.CacheControl)) shouldBe Some("no-cache")
  }

  test("change the ETag when the file changes") {
    val file = site / "assets" / "version.txt"
    FileSystem.writeString(file, "v1")
    val before = etagOf("/assets/version.txt")
    FileSystem.writeString(file, "version 2")
    etagOf("/assets/version.txt") shouldNotBe before
  }

  test("answer 304 without a body when If-None-Match matches") {
    val etag = etagOf("/assets/app.js")
    for value <- Seq(etag, s"W/${etag}", s"\"other\", ${etag}", "*") do
      val r = conditional("/assets/app.js", value)
      r.map(_.status) shouldBe Some(HttpStatus.NotModified_304)
      r.flatMap(_.contentAsString) shouldBe None
      r.flatMap(_.header(HttpHeader.ETag)) shouldBe Some(etag)
      r.flatMap(_.header(HttpHeader.CacheControl)) shouldBe Some("no-cache")
  }

  test("serve the file when If-None-Match does not match") {
    val r = conditional("/assets/app.js", "\"stale\"")
    r.map(_.status) shouldBe Some(HttpStatus.Ok_200)
    r.flatMap(_.contentAsString) shouldBe Some("console.log(1)")
  }

  test("answer 304 for the SPA fallback when the index file is unchanged") {
    val spa  = files.withSpaFallback(true)
    val etag = etagOf("/")
    val req  = Request
      .get("/chats/42")
      .addHeader(HttpHeader.Accept, "text/html")
      .addHeader(HttpHeader.IfNoneMatch, etag)
    spa(req, notFound).map { r =>
      r.status shouldBe HttpStatus.NotModified_304
    }
  }

  test("configure Cache-Control") {
    files
      .withCacheControl("max-age=60")
      .serve("/assets/app.js")
      .flatMap(_.header(HttpHeader.CacheControl)) shouldBe Some("max-age=60")
    files.noCacheControl.serve("/assets/app.js").flatMap(_.header(HttpHeader.CacheControl)) shouldBe
      None
    intercept[IllegalArgumentException] {
      files.withCacheControl("a\r\nSet-Cookie: x=1")
    }
  }

  private def ranged(path: String, headers: (String, String)*): Response =
    files.serve(headers.foldLeft(Request.get(path))((r, h) => r.addHeader(h._1, h._2))).get

  test("parse a single byte range") {
    import StaticFiles.ByteRange.*
    StaticFiles.parseRange("bytes=0-3", 10) shouldBe Partial(0, 3)
    StaticFiles.parseRange("bytes=4-", 10) shouldBe Partial(4, 9)
    StaticFiles.parseRange("bytes=-3", 10) shouldBe Partial(7, 9)
    StaticFiles.parseRange("bytes=-30", 10) shouldBe Partial(0, 9)
    StaticFiles.parseRange("bytes=5-100", 10) shouldBe Partial(5, 9)
    StaticFiles.parseRange("Bytes=1-2", 10) shouldBe Partial(1, 2)
  }

  test("treat several ranges, other units, and bad syntax as no Range") {
    import StaticFiles.ByteRange.*
    for value <- Seq(
        "bytes=0-1,4-5",
        "items=0-1",
        "bytes=3-1",
        "bytes=a-b",
        "bytes=-",
        "bytes=1",
        "bytes=+1-2",
        "bytes=99999999999999999999-"
      )
    do
      StaticFiles.parseRange(value, 10) shouldBe Full
  }

  test("find a range past the end unsatisfiable") {
    import StaticFiles.ByteRange.*
    StaticFiles.parseRange("bytes=10-", 10) shouldBe Unsatisfiable
    StaticFiles.parseRange("bytes=-0", 10) shouldBe Unsatisfiable
    StaticFiles.parseRange("bytes=-1", 0) shouldBe Unsatisfiable
  }

  test("answer a Range with 206 Partial Content") {
    val r = ranged("/assets/digits.txt", HttpHeader.Range -> "bytes=2-5")
    r.status shouldBe HttpStatus.PartialContent_206
    r.contentAsString shouldBe Some("2345")
    r.header(HttpHeader.ContentRange) shouldBe Some("bytes 2-5/10")
    r.header(HttpHeader.AcceptRanges) shouldBe Some("bytes")
    r.header(HttpHeader.ETag) shouldBe Some(etagOf("/assets/digits.txt"))
    r.header("X-Content-Type-Options") shouldBe Some("nosniff")
    r.contentType.map(_.fullType) shouldBe Some("text/plain")
    ranged("/assets/digits.txt", HttpHeader.Range -> "bytes=-3").contentAsString shouldBe
      Some("789")
  }

  test("answer 416 for a range past the end of the file") {
    val r = ranged("/assets/digits.txt", HttpHeader.Range -> "bytes=10-")
    r.status shouldBe HttpStatus.RangeNotSatisfiable_416
    r.header(HttpHeader.ContentRange) shouldBe Some("bytes */10")
    r.contentAsString shouldBe None
    ranged("/assets/empty.txt", HttpHeader.Range -> "bytes=0-").status shouldBe
      HttpStatus.RangeNotSatisfiable_416
  }

  test("serve the whole file for several ranges") {
    val r = ranged("/assets/digits.txt", HttpHeader.Range -> "bytes=0-1,4-5")
    r.status shouldBe HttpStatus.Ok_200
    r.contentAsString shouldBe Some("0123456789")
    r.header(HttpHeader.ContentRange) shouldBe None
  }

  test("honor the Range only when If-Range matches the ETag") {
    val etag    = etagOf("/assets/digits.txt")
    val current = ranged(
      "/assets/digits.txt",
      HttpHeader.Range   -> "bytes=0-0",
      HttpHeader.IfRange -> etag
    )
    current.status shouldBe HttpStatus.PartialContent_206
    current.contentAsString shouldBe Some("0")
    for stale <- Seq("\"stale\"", s"W/${etag}", "Thu, 01 Jan 2026 00:00:00 GMT") do
      val r = ranged(
        "/assets/digits.txt",
        HttpHeader.Range   -> "bytes=0-0",
        HttpHeader.IfRange -> stale
      )
      r.status shouldBe HttpStatus.Ok_200
      r.contentAsString shouldBe Some("0123456789")
  }

  test("answer 304 before looking at the Range") {
    val etag = etagOf("/assets/digits.txt")
    ranged("/assets/digits.txt", HttpHeader.Range -> "bytes=0-0", HttpHeader.IfNoneMatch -> etag)
      .status shouldBe HttpStatus.NotModified_304
  }

  test("send file bodies as FileContent, read only on demand") {
    val r = files.serve("/assets/digits.txt").get
    r.header(HttpHeader.AcceptRanges) shouldBe Some("bytes")
    r.content shouldMatch { case HttpContent.FileContent(_, 0, 10, _) =>
    }
    // An empty file needs no file body
    files.serve("/assets/empty.txt").map(_.content) shouldBe Some(HttpContent.Empty)
  }

  test("read a FileContent range") {
    val c = HttpContent.FileContent(site / "assets" / "digits.txt", 3, 4)
    c.toContentString shouldBe "3456"
    c.length shouldBe 4
    // A range past the end of the file fails instead of returning short content
    intercept[Exception] {
      HttpContent.FileContent(site / "assets" / "digits.txt", 8, 4).toContentBytes
    }
  }

end StaticFilesTest
