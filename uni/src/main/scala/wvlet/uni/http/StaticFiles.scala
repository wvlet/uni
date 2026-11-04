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

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
  * Serves files under a root directory, e.g. a built web frontend next to an API:
  * {{{
  * Http.server.withFilter(StaticFiles("dist").withSpaFallback(true)).withRxHandler(api)
  * }}}
  *
  * A `GET` for an existing file under `root` returns it; every other request goes to the next
  * handler. It is safe by default: paths with `..`, `\`, `:`, NUL or control characters, symbolic
  * links below the root, dotfiles (except `.well-known/`), and directories without an index file
  * are never served. Every file response sets `X-Content-Type-Options: nosniff`.
  *
  * Files are read whole on each request, so it suits app bundles rather than large media. It runs
  * on the JVM, Node.js, and Scala Native, and fails at construction in a browser.
  *
  * @param root
  *   the directory to serve. A relative path resolves against the current directory. It is read on
  *   each request, so it may be created after the server starts
  */
case class StaticFiles(
    root: String,
    // Serve indexFile for a GET that accepts text/html when the next handler answers 404
    spaFallback: Boolean = false,
    // Serve files and directories whose name starts with '.' (`.well-known` is always served)
    dotfiles: Boolean = false,
    indexFile: String = "index.html",
    // Content-Type by lower-case file extension; others are application/octet-stream
    contentTypes: Map[String, ContentType] = StaticFiles.defaultContentTypes
) extends RxHttpFilter:
  require(
    !FileSystem.isBrowser,
    "StaticFiles needs a file system: use it on the JVM, Node.js, or Scala Native"
  )

  require(
    StaticFiles.isSafeSegment(indexFile, dotfiles = false),
    s"indexFile must be a plain file name: ${indexFile}"
  )

  private lazy val rootPath: IOPath =
    val p   = IOPath.parse(root)
    val abs =
      if p.isAbsolute then
        p
      else
        FileSystem.currentDirectory.resolve(p)
    abs.normalize

  def withSpaFallback(enabled: Boolean): StaticFiles = copy(spaFallback = enabled)
  def noSpaFallback: StaticFiles                     = withSpaFallback(false)
  def withDotfiles(enabled: Boolean): StaticFiles    = copy(dotfiles = enabled)
  def noDotfiles: StaticFiles                        = withDotfiles(false)
  def withIndexFile(name: String): StaticFiles       = copy(indexFile = name)

  /** Add or override the Content-Type for a file extension (without the dot). */
  def withContentType(extension: String, contentType: ContentType): StaticFiles = copy(
    contentTypes = contentTypes + (extension.toLowerCase -> contentType)
  )

  override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    if request.method != HttpMethod.GET then
      next.handle(request)
    else
      serve(request.path) match
        case Some(response) =>
          Rx.single(response)
        case None if spaFallback && acceptsHtml(request) =>
          next
            .handle(request)
            .map { response =>
              if response.status == HttpStatus.NotFound_404 then
                serve("/").getOrElse(response)
              else
                response
            }
        case None =>
          next.handle(request)

  /** The response for a URL path, or None when no servable file matches it. */
  def serve(urlPath: String): Option[Response] = StaticFiles
    .segments(urlPath, dotfiles)
    .flatMap { (segments, isDirectory) =>
      // A directory is served only with a trailing slash, so relative URLs in its index resolve
      findFile(
        if isDirectory then
          segments :+ indexFile
        else
          segments
      )
    }
    .flatMap(file)

  /**
    * The file at `segments` under the root, when every component exists and none is a symbolic
    * link. Each component is checked with lstat, so a link anywhere below the root is refused.
    */
  private def findFile(segments: Seq[String]): Option[IOPath] =
    var path  = rootPath
    var found = segments.nonEmpty
    val it    = segments.iterator
    while found && it.hasNext do
      path = IOPath(path.segments :+ it.next(), path.isAbsolute)
      val info = FileSystem.info(path)
      // A junction or other reparse point is FileType.Other, so it fails both checks
      found =
        if it.hasNext then
          info.isDirectory
        else
          info.isFile
    Option.when(found)(path)

  /** The file as a response, or None when it can't be read (permissions, deleted meanwhile). */
  private def file(path: IOPath): Option[Response] =
    val contentType = contentTypes.getOrElse(
      path.extension.toLowerCase,
      ContentType.ApplicationOctetStream
    )
    try
      Some(
        Response
          .ok(HttpContent.bytes(FileSystem.readBytes(path), contentType))
          .addHeader("X-Content-Type-Options", "nosniff")
      )
    catch
      case _: Exception =>
        None

  private def acceptsHtml(request: Request): Boolean = request
    .headers
    .get(HttpHeader.Accept)
    .exists(_.toLowerCase.contains("text/html"))

end StaticFiles

object StaticFiles:

  private val utf8Text = Seq(
    "html"        -> "text/html",
    "htm"         -> "text/html",
    "css"         -> "text/css",
    "js"          -> "text/javascript",
    "mjs"         -> "text/javascript",
    "txt"         -> "text/plain",
    "xml"         -> "application/xml",
    "json"        -> "application/json",
    "map"         -> "application/json",
    "svg"         -> "image/svg+xml",
    "webmanifest" -> "application/manifest+json"
  ).map((ext, t) => ext -> ContentType(s"${t}; charset=utf-8"))

  private val binary = Seq(
    "png"   -> "image/png",
    "jpg"   -> "image/jpeg",
    "jpeg"  -> "image/jpeg",
    "gif"   -> "image/gif",
    "webp"  -> "image/webp",
    "avif"  -> "image/avif",
    "ico"   -> "image/x-icon",
    "woff"  -> "font/woff",
    "woff2" -> "font/woff2",
    "ttf"   -> "font/ttf",
    "otf"   -> "font/otf",
    "wasm"  -> "application/wasm",
    "pdf"   -> "application/pdf",
    "mp4"   -> "video/mp4",
    "webm"  -> "video/webm",
    "mp3"   -> "audio/mpeg"
  ).map((ext, t) => ext -> ContentType(t))

  /** Content types for the extensions of a typical web build. */
  val defaultContentTypes: Map[String, ContentType] = (utf8Text ++ binary).toMap

  /**
    * Split a URL path into decoded file name segments, and whether it names a directory (ends with
    * `/`). None when any segment is unsafe to map onto a file system.
    */
  private[http] def segments(urlPath: String, dotfiles: Boolean): Option[(Seq[String], Boolean)] =
    if !urlPath.startsWith("/") then
      None
    else
      val rest        = urlPath.substring(1)
      val isDirectory = rest.isEmpty || rest.endsWith("/")
      val raw         =
        if rest.isEmpty then
          Seq.empty
        else
          rest.stripSuffix("/").split("/", -1).toSeq
      val decoded = raw.map(percentDecode)
      if decoded.forall(_.isDefined) then
        val names = decoded.flatten
        // `.well-known` (RFC 8615) is public by design, so it stays served without dotfiles
        val safe = names
          .zipWithIndex
          .forall { (name, i) =>
            isSafeSegment(name, dotfiles || (i == 0 && name == ".well-known"))
          }
        Option.when(safe)((names, isDirectory))
      else
        None

  // Names Windows can't open (Paths.get throws) or silently aliases to another name
  private val windowsReserved = Set('<', '>', '"', '|', '?', '*')
  // An 8.3 short name such as GIT~1, which on NTFS can alias a hidden `.git`
  private val shortNameAlias = ".*~[0-9].*".r

  private[http] def isSafeSegment(name: String, dotfiles: Boolean): Boolean =
    name.nonEmpty && name != "." && name != ".." &&
      (dotfiles || !name.startsWith(".")) && !name.endsWith(".") && !name.endsWith(" ") &&
      !shortNameAlias.matches(name) &&
      name.forall(c =>
        c >= ' ' && c != '\u007f' && c != '/' && c != '\\' && c != ':' &&
          !windowsReserved.contains(c)
      )

  /** Decode `%XX` escapes as UTF-8. None for a malformed escape. */
  private[http] def percentDecode(s: String): Option[String] =
    if !s.contains('%') then
      Some(s)
    else
      val out = ByteArrayOutputStream()
      var i   = 0
      var ok  = true
      while ok && i < s.length do
        if s.charAt(i) == '%' then
          val (h1, h2) =
            if i + 2 < s.length then
              (hex(s.charAt(i + 1)), hex(s.charAt(i + 2)))
            else
              (-1, -1)
          if h1 >= 0 && h2 >= 0 then
            out.write(h1 * 16 + h2)
            i += 3
          else
            ok = false
        else
          // Copy the run up to the next escape at once, keeping surrogate pairs together
          val end =
            s.indexOf('%', i) match
              case -1 =>
                s.length
              case n =>
                n
          val bytes = s.substring(i, end).getBytes(StandardCharsets.UTF_8)
          out.write(bytes, 0, bytes.length)
          i = end
      Option.when(ok)(String(out.toByteArray, StandardCharsets.UTF_8))

  private def hex(c: Char): Int =
    if c >= '0' && c <= '9' then
      c - '0'
    else if c >= 'a' && c <= 'f' then
      c - 'a' + 10
    else if c >= 'A' && c <= 'F' then
      c - 'A' + 10
    else
      -1

end StaticFiles
