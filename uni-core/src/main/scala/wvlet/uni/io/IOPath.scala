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

/**
  * Cross-platform path abstraction. Represents a file or directory path that works consistently
  * across JVM, Scala.js (Node.js and browser), and Scala Native.
  */
case class IOPath(segments: Seq[String], isAbsolute: Boolean):
  /**
    * Returns the path as a string using the platform-specific separator.
    */
  def path: String =
    val sep = IOPath.separator
    if isAbsolute then
      if IOPath.isWindows && hasDrive then
        // Windows absolute path with drive letter (e.g., C:\foo\bar). A drive root keeps its
        // separator: `C:` alone means the current folder on drive C, not its root
        if isDriveRoot then
          segments.head + sep
        else
          segments.mkString(sep)
      else
        // Unix absolute path (e.g., /foo/bar)
        sep + segments.mkString(sep)
    else
      segments.mkString(sep)

  /**
    * Returns the path as a POSIX string (using forward slashes).
    */
  def posixPath: String =
    if isAbsolute then
      if hasDrive then
        // Windows drive letter - convert backslash style. A drive root renders as `C:/`
        if isDriveRoot then
          segments.head + "/"
        else
          segments.mkString("/")
      else
        "/" + segments.mkString("/")
    else
      segments.mkString("/")

  /**
    * Returns the file name (last segment of the path).
    */
  def fileName: String = segments.lastOption.getOrElse("")

  /**
    * Returns the file extension (without the dot), or empty string if none.
    */
  def extension: String =
    val name = fileName
    val idx  = name.lastIndexOf('.')
    if idx > 0 then
      name.substring(idx + 1)
    else if idx == 0 && name.length > 1 then
      name.substring(1) // Handle dotfiles like .gitignore -> gitignore
    else
      ""

  /**
    * Returns the file name without extension.
    */
  def baseName: String =
    val name = fileName
    val idx  = name.lastIndexOf('.')
    if idx > 0 then
      name.substring(0, idx)
    else if idx == 0 then
      "" // Handle dotfiles like .gitignore
    else
      name

  /**
    * Returns the parent path, or None if this is the root or has no parent.
    */
  def parent: Option[IOPath] =
    if segments.isEmpty || isDriveRoot then
      None
    else if segments.size == 1 then
      if isAbsolute then
        Some(IOPath(Seq.empty, isAbsolute = true))
      else
        None
    else
      Some(IOPath(segments.dropRight(1), isAbsolute))

  /**
    * Resolves a child path relative to this path.
    */
  def resolve(child: String): IOPath =
    val childPath = IOPath.parse(child)
    if childPath.isAbsolute then
      childPath
    else
      IOPath(segments ++ childPath.segments, isAbsolute)

  /**
    * Resolves a child path relative to this path.
    */
  def resolve(child: IOPath): IOPath =
    if child.isAbsolute then
      child
    else
      IOPath(segments ++ child.segments, isAbsolute)

  /**
    * Alias for resolve.
    */
  def /(child: String): IOPath = resolve(child)

  /**
    * Alias for resolve.
    */
  def /(child: IOPath): IOPath = resolve(child)

  /**
    * Returns the path relative to the given base path.
    */
  def relativeTo(base: IOPath): IOPath =
    if isAbsolute != base.isAbsolute then
      this // Cannot relativize paths with different absolute status
    else
      val commonPrefixLength = segments.zip(base.segments).takeWhile((a, b) => a == b).length
      val ups                = Seq.fill(base.segments.length - commonPrefixLength)("..")
      val remaining          = segments.drop(commonPrefixLength)
      IOPath(ups ++ remaining, isAbsolute = false)

  /**
    * Normalizes the path by resolving `.` and `..` segments.
    */
  def normalize: IOPath =
    val normalized =
      segments.foldLeft(List.empty[String]) { (acc, segment) =>
        segment match
          case "." =>
            acc
          case ".." if acc.nonEmpty && acc.last != ".." =>
            // `..` at a drive root stays at the root, as `..` at `/` does
            if isAbsolute && acc.size == 1 && hasDrive then
              acc
            else
              acc.dropRight(1)
          case ".." if !isAbsolute =>
            acc :+ ".."
          case ".." =>
            acc // Ignore .. at root
          case s =>
            acc :+ s
      }
    IOPath(normalized, isAbsolute)

  /**
    * Returns true if this path starts with the given path.
    */
  def startsWith(other: IOPath): Boolean =
    isAbsolute == other.isAbsolute && segments.take(other.segments.length) == other.segments

  /**
    * Returns true if this path ends with the given path.
    */
  def endsWith(other: IOPath): Boolean =
    if other.isAbsolute then
      this == other
    else
      segments.takeRight(other.segments.length) == other.segments

  /**
    * Returns the number of segments in the path.
    */
  def segmentCount: Int = segments.length

  /**
    * Returns the root path if this is an absolute path, None otherwise.
    */
  def root: Option[IOPath] =
    if isAbsolute then
      if hasDrive then
        // Windows drive root
        Some(IOPath(Seq(segments.head), isAbsolute = true))
      else
        Some(IOPath(Seq.empty, isAbsolute = true))
    else
      None

  /**
    * True for an absolute path whose first segment is a Windows drive (`C:`), matching how
    * [[IOPath.parse]] detects one
    */
  private def hasDrive: Boolean =
    isAbsolute && segments.headOption.exists(s => s.length >= 2 && s.charAt(1) == ':')

  /** True for a drive root such as `C:\` */
  private def isDriveRoot: Boolean = hasDrive && segments.size == 1 && segments.head.length == 2

  override def toString: String = path

end IOPath

object IOPath:
  /**
    * Platform-specific path separator. Set by platform implementations.
    */
  var separator: String = "/"

  /**
    * Whether the current platform is Windows. Set by platform implementations.
    */
  var isWindows: Boolean = false

  /**
    * Parses a path string into an IOPath.
    */
  def parse(pathStr: String): IOPath =
    if pathStr.isEmpty then
      IOPath(Seq.empty, isAbsolute = false)
    else
      // Normalize separators
      val normalized = stripWin32Prefix(pathStr).replace("\\", "/")

      // Check for absolute path
      val isAbs =
        normalized.startsWith("/") ||
          (normalized.length >= 2 && normalized(1) == ':') // Windows drive letter

      // Split into segments
      val parts = normalized.split("/").filter(_.nonEmpty).toSeq

      IOPath(parts, isAbs)

  /**
    * Removes the Windows `\\?\` (extended-length) or `\\.\` (device) prefix from a drive or UNC
    * path: `\\?\C:\x` becomes `C:\x`, and `\\?\UNC\server\share\x` becomes `\\server\share\x`. The
    * prefix only lifts the 260-character limit of Win32 calls, and the JVM, Node.js, and Windows
    * add it back where they need it. Other prefixed paths, such as `\\.\pipe\name` or
    * `\\?\Volume{...}\`, name devices or volumes that IOPath can't represent, so they are rejected.
    * Only the backslash form is a prefix: `//?/x` stays a POSIX path.
    */
  private[uni] def stripWin32Prefix(pathStr: String): String =
    if !(pathStr.startsWith("\\\\?\\") || pathStr.startsWith("\\\\.\\")) then
      pathStr
    else
      val rest                   = pathStr.substring(4)
      def isSep(i: Int): Boolean =
        rest.length > i && (rest.charAt(i) == '\\' || rest.charAt(i) == '/')
      // `\\.\C:` without a separator opens the volume itself, not its root folder
      val isDrive = rest.length >= 3 && rest.charAt(0).isLetter && rest.charAt(1) == ':' && isSep(2)
      val isUnc   = rest.take(3).equalsIgnoreCase("UNC") && isSep(3) && rest.length > 4
      if isDrive then
        rest
      else if isUnc then
        s"\\\\${rest.substring(4)}"
      else
        val prefix = pathStr.take(4)
        throw IllegalArgumentException(
          s"IOPath can't represent the Windows device path ${pathStr}: only a drive or UNC path can follow ${prefix}"
        )

  /**
    * Creates an IOPath from the given path string.
    */
  def apply(pathStr: String): IOPath = parse(pathStr)

  /**
    * Creates an IOPath by joining multiple path segments.
    */
  def of(first: String, rest: String*): IOPath =
    rest.foldLeft(parse(first))((path, segment) => path.resolve(segment))

  /**
    * Returns the current working directory. Implemented by platform-specific code.
    */
  def currentDir: IOPath = FileSystem.currentDirectory

  /**
    * Returns the user's home directory. Implemented by platform-specific code.
    */
  def homeDir: IOPath = FileSystem.homeDirectory

  /**
    * Returns the system's temporary directory. Implemented by platform-specific code.
    */
  def tempDir: IOPath = FileSystem.tempDirectory

end IOPath
