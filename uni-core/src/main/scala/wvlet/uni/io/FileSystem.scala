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

import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.Future

/**
  * Cross-platform exception for file not found errors.
  */
class NoSuchFileException(message: String) extends Exception(message)

/**
  * Cross-platform exception for I/O operation errors.
  */
class IOOperationException(message: String) extends Exception(message)

/**
  * Cross-platform exception for file already exists errors.
  */
class FileAlreadyExistsException(message: String) extends Exception(message)

/**
  * Write mode for file operations.
  */
enum WriteMode:
  /** Create a new file, fail if it already exists */
  case CreateNew

  /** Create or truncate the file */
  case Create

  /** Append to the file, create if it doesn't exist */
  case Append

/**
  * Options for listing directory contents.
  */
case class ListOptions(
    /** Include hidden files in the listing */
    includeHidden: Boolean = false,
    /** Recursively list subdirectories */
    recursive: Boolean = false,
    /** Maximum depth for recursive listing (None = unlimited) */
    maxDepth: Option[Int] = None,
    /** Filter by file extension (e.g., "txt", "scala") */
    extensions: Seq[String] = Seq.empty,
    /** Filter by glob pattern (e.g., "*.scala", "**&#47;*.txt") */
    glob: Option[String] = None
):
  def withIncludeHidden(value: Boolean): ListOptions = copy(includeHidden = value)
  def withRecursive(value: Boolean): ListOptions     = copy(recursive = value)
  def withMaxDepth(depth: Int): ListOptions          = copy(maxDepth = Some(depth))
  def noMaxDepth: ListOptions                        = copy(maxDepth = None)
  def withExtensions(exts: String*): ListOptions     = copy(extensions = exts)
  def withGlob(pattern: String): ListOptions         = copy(glob = Some(pattern))
  def noGlob: ListOptions                            = copy(glob = None)

object ListOptions:
  val default: ListOptions = ListOptions()

  /**
    * Converts a glob pattern to a regex for file matching.
    */
  def globToRegex(glob: String): scala.util.matching.Regex =
    val regexStr = glob
      .replace(".", "\\.")
      .replace("**", "<<<DOUBLESTAR>>>")
      .replace("*", "[^/\\\\]*")
      .replace("<<<DOUBLESTAR>>>", ".*")
      .replace("?", ".")
    ("^" + regexStr + "$").r

/**
  * Options for copy operations.
  */
case class CopyOptions(
    /** Overwrite existing files */
    overwrite: Boolean = false,
    /** Preserve file attributes (modification time, permissions) */
    preserveAttributes: Boolean = false,
    /** Copy directories recursively */
    recursive: Boolean = true
):
  def withOverwrite(value: Boolean): CopyOptions          = copy(overwrite = value)
  def withPreserveAttributes(value: Boolean): CopyOptions = copy(preserveAttributes = value)
  def withRecursive(value: Boolean): CopyOptions          = copy(recursive = value)

object CopyOptions:
  val default: CopyOptions = CopyOptions()

/**
  * Base trait for cross-platform file system operations. Platform-specific implementations provide
  * the actual functionality.
  *
  * The FileSystem API provides both synchronous and asynchronous operations:
  *   - Sync operations (e.g., readString, writeString) work on JVM and Scala Native
  *   - Async operations (e.g., readStringAsync, writeStringAsync) work on all platforms
  *
  * For browser environments, only async operations are available.
  */
trait FileSystemBase:
  // ============================================================
  // Platform information
  // ============================================================

  /**
    * Returns the current working directory.
    */
  def currentDirectory: IOPath

  /**
    * Returns the user's home directory.
    */
  def homeDirectory: IOPath

  /**
    * Returns the system's temporary directory.
    */
  def tempDirectory: IOPath

  /**
    * Returns true if this is a browser environment.
    */
  def isBrowser: Boolean = false

  /**
    * Returns true if this is a Node.js environment.
    */
  def isNode: Boolean = false

  // ============================================================
  // Existence and type checks
  // ============================================================

  /**
    * Returns true if the path exists.
    */
  def exists(path: IOPath): Boolean

  /** String-path overload of [[exists]]. */
  def exists(path: String): Boolean = exists(IOPath.parse(path))

  /**
    * Returns true if the path is a regular file.
    */
  def isFile(path: IOPath): Boolean

  /** String-path overload of [[isFile]]. */
  def isFile(path: String): Boolean = isFile(IOPath.parse(path))

  /**
    * Returns true if the path is a directory.
    */
  def isDirectory(path: IOPath): Boolean

  /** String-path overload of [[isDirectory]]. */
  def isDirectory(path: String): Boolean = isDirectory(IOPath.parse(path))

  /**
    * Returns file information for the given path.
    */
  def info(path: IOPath): FileInfo

  /** String-path overload of [[info]]. */
  def info(path: String): FileInfo = info(IOPath.parse(path))

  // ============================================================
  // Synchronous read operations
  // ============================================================

  /**
    * Reads the entire file as a string (UTF-8 encoding).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readString(path: IOPath): String

  /** String-path overload of [[readString]]. */
  def readString(path: String): String = readString(IOPath.parse(path))

  /**
    * Reads the entire file as a byte array.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readBytes(path: IOPath): Array[Byte]

  /** String-path overload of [[readBytes]]. */
  def readBytes(path: String): Array[Byte] = readBytes(IOPath.parse(path))

  /**
    * Reads the file line by line.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readLines(path: IOPath): Seq[String]

  /** String-path overload of [[readLines]]. */
  def readLines(path: String): Seq[String] = readLines(IOPath.parse(path))

  // ============================================================
  // Streaming read operations
  // ============================================================

  /**
    * Reads the file in fixed-size byte chunks. Returns an Iterator over byte arrays for processing
    * large files incrementally.
    *
    * On JVM and Native, the returned iterator implements AutoCloseable.
    *
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readChunks(path: IOPath, chunkSize: Int = 8192): Iterator[Array[Byte]]

  /** String-path overload of [[readChunks]]. */
  def readChunks(path: String, chunkSize: Int): Iterator[Array[Byte]] = readChunks(
    IOPath.parse(path),
    chunkSize
  )

  /** String-path overload of [[readChunks]] with default chunk size. */
  def readChunks(path: String): Iterator[Array[Byte]] = readChunks(IOPath.parse(path))

  /**
    * Opens a file and returns an InputStream for streaming reads. The caller is responsible for
    * closing the stream.
    *
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readStream(path: IOPath): InputStream

  /** String-path overload of [[readStream]]. */
  def readStream(path: String): InputStream = readStream(IOPath.parse(path))

  /**
    * Opens a file and returns an OutputStream for streaming writes. The caller is responsible for
    * closing the stream.
    *
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeStream(path: IOPath, mode: WriteMode = WriteMode.Create): OutputStream

  /** String-path overload of [[writeStream]]. */
  def writeStream(path: String, mode: WriteMode): OutputStream = writeStream(
    IOPath.parse(path),
    mode
  )

  /** String-path overload of [[writeStream]] with default write mode. */
  def writeStream(path: String): OutputStream = writeStream(IOPath.parse(path))

  // ============================================================
  // Synchronous write operations
  // ============================================================

  /**
    * Writes a string to a file (UTF-8 encoding).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeString(path: IOPath, content: String, mode: WriteMode = WriteMode.Create): Unit

  /** String-path overload of [[writeString]]. */
  def writeString(path: String, content: String, mode: WriteMode): Unit = writeString(
    IOPath.parse(path),
    content,
    mode
  )

  /** String-path overload of [[writeString]] with default write mode. */
  def writeString(path: String, content: String): Unit = writeString(IOPath.parse(path), content)

  /**
    * Writes a byte array to a file.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode = WriteMode.Create): Unit

  /** String-path overload of [[writeBytes]]. */
  def writeBytes(path: String, content: Array[Byte], mode: WriteMode): Unit = writeBytes(
    IOPath.parse(path),
    content,
    mode
  )

  /** String-path overload of [[writeBytes]] with default write mode. */
  def writeBytes(path: String, content: Array[Byte]): Unit = writeBytes(IOPath.parse(path), content)

  /**
    * Appends a string to a file (UTF-8 encoding).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def appendString(path: IOPath, content: String): Unit = writeString(
    path,
    content,
    WriteMode.Append
  )

  /** String-path overload of [[appendString]]. */
  def appendString(path: String, content: String): Unit = appendString(IOPath.parse(path), content)

  /**
    * Appends a byte array to a file.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def appendBytes(path: IOPath, content: Array[Byte]): Unit = writeBytes(
    path,
    content,
    WriteMode.Append
  )

  /** String-path overload of [[appendBytes]]. */
  def appendBytes(path: String, content: Array[Byte]): Unit = appendBytes(
    IOPath.parse(path),
    content
  )

  // ============================================================
  // Directory operations
  // ============================================================

  /**
    * Lists the contents of a directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def list(path: IOPath, options: ListOptions = ListOptions.default): Seq[IOPath]

  /** String-path overload of [[list]]. */
  def list(path: String, options: ListOptions): Seq[IOPath] = list(IOPath.parse(path), options)

  /** String-path overload of [[list]] with default options. */
  def list(path: String): Seq[IOPath] = list(IOPath.parse(path))

  /**
    * Creates a directory (and parent directories if needed).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createDirectory(path: IOPath): Unit

  /** String-path overload of [[createDirectory]]. */
  def createDirectory(path: String): Unit = createDirectory(IOPath.parse(path))

  /**
    * Creates a directory only if it doesn't exist.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createDirectoryIfNotExists(path: IOPath): Unit =
    if !exists(path) then
      createDirectory(path)

  /** String-path overload of [[createDirectoryIfNotExists]]. */
  def createDirectoryIfNotExists(path: String): Unit = createDirectoryIfNotExists(
    IOPath.parse(path)
  )

  // ============================================================
  // Delete operations
  // ============================================================

  /**
    * Deletes a file or empty directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def delete(path: IOPath): Boolean

  /** String-path overload of [[delete]]. */
  def delete(path: String): Boolean = delete(IOPath.parse(path))

  /**
    * Deletes a file or directory recursively.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def deleteRecursively(path: IOPath): Boolean

  /** String-path overload of [[deleteRecursively]]. */
  def deleteRecursively(path: String): Boolean = deleteRecursively(IOPath.parse(path))

  /**
    * Deletes a file if it exists.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def deleteIfExists(path: IOPath): Boolean =
    if exists(path) then
      delete(path)
    else
      false

  /** String-path overload of [[deleteIfExists]]. */
  def deleteIfExists(path: String): Boolean = deleteIfExists(IOPath.parse(path))

  // ============================================================
  // Copy and move operations
  // ============================================================

  /**
    * Copies a file or directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def copy(source: IOPath, target: IOPath, options: CopyOptions = CopyOptions.default): Unit

  /** String-path overload of [[copy]]. */
  def copy(source: String, target: String, options: CopyOptions): Unit = copy(
    IOPath.parse(source),
    IOPath.parse(target),
    options
  )

  /** String-path overload of [[copy]] with default options. */
  def copy(source: String, target: String): Unit = copy(IOPath.parse(source), IOPath.parse(target))

  /**
    * Moves (renames) a file or directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def move(source: IOPath, target: IOPath, overwrite: Boolean = false): Unit

  /** String-path overload of [[move]]. */
  def move(source: String, target: String, overwrite: Boolean): Unit = move(
    IOPath.parse(source),
    IOPath.parse(target),
    overwrite
  )

  /** String-path overload of [[move]] with default overwrite=false. */
  def move(source: String, target: String): Unit = move(IOPath.parse(source), IOPath.parse(target))

  // ============================================================
  // Temporary file operations
  // ============================================================

  /**
    * Creates a temporary file.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createTempFile(
      prefix: String = "tmp",
      suffix: String = ".tmp",
      directory: Option[IOPath] = None
  ): IOPath

  /**
    * Creates a temporary directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createTempDirectory(prefix: String = "tmp", directory: Option[IOPath] = None): IOPath

  // ============================================================
  // Asynchronous operations (available on all platforms)
  // ============================================================

  /**
    * Reads the entire file as a string asynchronously.
    */
  def readStringAsync(path: IOPath): Future[String]

  /** String-path overload of [[readStringAsync]]. */
  def readStringAsync(path: String): Future[String] = readStringAsync(IOPath.parse(path))

  /**
    * Reads the entire file as a byte array asynchronously.
    */
  def readBytesAsync(path: IOPath): Future[Array[Byte]]

  /** String-path overload of [[readBytesAsync]]. */
  def readBytesAsync(path: String): Future[Array[Byte]] = readBytesAsync(IOPath.parse(path))

  /**
    * Writes a string to a file asynchronously.
    */
  def writeStringAsync(
      path: IOPath,
      content: String,
      mode: WriteMode = WriteMode.Create
  ): Future[Unit]

  /** String-path overload of [[writeStringAsync]]. */
  def writeStringAsync(path: String, content: String, mode: WriteMode): Future[Unit] =
    writeStringAsync(IOPath.parse(path), content, mode)

  /** String-path overload of [[writeStringAsync]] with default write mode. */
  def writeStringAsync(path: String, content: String): Future[Unit] = writeStringAsync(
    IOPath.parse(path),
    content
  )

  /**
    * Writes a byte array to a file asynchronously.
    */
  def writeBytesAsync(
      path: IOPath,
      content: Array[Byte],
      mode: WriteMode = WriteMode.Create
  ): Future[Unit]

  /** String-path overload of [[writeBytesAsync]]. */
  def writeBytesAsync(path: String, content: Array[Byte], mode: WriteMode): Future[Unit] =
    writeBytesAsync(IOPath.parse(path), content, mode)

  /** String-path overload of [[writeBytesAsync]] with default write mode. */
  def writeBytesAsync(path: String, content: Array[Byte]): Future[Unit] = writeBytesAsync(
    IOPath.parse(path),
    content
  )

  /**
    * Lists the contents of a directory asynchronously.
    */
  def listAsync(path: IOPath, options: ListOptions = ListOptions.default): Future[Seq[IOPath]]

  /** String-path overload of [[listAsync]]. */
  def listAsync(path: String, options: ListOptions): Future[Seq[IOPath]] = listAsync(
    IOPath.parse(path),
    options
  )

  /** String-path overload of [[listAsync]] with default options. */
  def listAsync(path: String): Future[Seq[IOPath]] = listAsync(IOPath.parse(path))

  /**
    * Returns file information asynchronously.
    */
  def infoAsync(path: IOPath): Future[FileInfo]

  /** String-path overload of [[infoAsync]]. */
  def infoAsync(path: String): Future[FileInfo] = infoAsync(IOPath.parse(path))

  /**
    * Checks if a path exists asynchronously.
    */
  def existsAsync(path: IOPath): Future[Boolean]

  /** String-path overload of [[existsAsync]]. */
  def existsAsync(path: String): Future[Boolean] = existsAsync(IOPath.parse(path))

  // ============================================================
  // Symlink operations
  // ============================================================

  /**
    * Creates a symbolic link at `link` pointing to `target`.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createSymlink(link: IOPath, target: IOPath): Unit

  /** String-path overload of [[createSymlink]]. */
  def createSymlink(link: String, target: String): Unit = createSymlink(
    IOPath.parse(link),
    IOPath.parse(target)
  )

  /**
    * Reads the target of a symbolic link without following it.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readSymlink(link: IOPath): IOPath

  /** String-path overload of [[readSymlink]]. */
  def readSymlink(link: String): IOPath = readSymlink(IOPath.parse(link))

  // ============================================================
  // Permission operations
  // ============================================================

  /**
    * Returns the POSIX file permissions for the given path.
    * @throws UnsupportedOperationException
    *   on platforms that do not support POSIX permissions
    */
  def permissions(path: IOPath): PermSet =
    throw UnsupportedOperationException("POSIX permissions are not supported on this platform")

  /** String-path overload of [[permissions]]. */
  def permissions(path: String): PermSet = permissions(IOPath.parse(path))

  /**
    * Sets the POSIX file permissions for the given path.
    * @throws UnsupportedOperationException
    *   on platforms that do not support POSIX permissions
    */
  def setPermissions(path: IOPath, permissions: PermSet): Unit =
    throw UnsupportedOperationException("POSIX permissions are not supported on this platform")

  /** String-path overload of [[setPermissions]]. */
  def setPermissions(path: String, permissions: PermSet): Unit = setPermissions(
    IOPath.parse(path),
    permissions
  )

  /**
    * Returns the file owner name for the given path.
    * @throws UnsupportedOperationException
    *   on platforms that do not support owner information (e.g., JS)
    */
  def owner(path: IOPath): String =
    throw UnsupportedOperationException("File owner is not supported on this platform")

  /** String-path overload of [[owner]]. */
  def owner(path: String): String = owner(IOPath.parse(path))

  /**
    * Returns the file group name for the given path.
    * @throws UnsupportedOperationException
    *   on platforms that do not support group information (e.g., JS)
    */
  def group(path: IOPath): String =
    throw UnsupportedOperationException("File group is not supported on this platform")

  /** String-path overload of [[group]]. */
  def group(path: String): String = group(IOPath.parse(path))

end FileSystemBase

/**
  * FileSystem companion object. Platform-specific implementations provide the actual FileSystem
  * instance.
  */
object FileSystem extends FileSystemBase:
  import scala.compiletime.uninitialized

  // Delegate all operations to the platform-specific implementation
  @volatile
  private var _impl: FileSystemBase = uninitialized

  /**
    * Sets the platform-specific implementation. Called by platform initialization code.
    */
  private[io] def setImplementation(impl: FileSystemBase): Unit = _impl = impl

  private def impl: FileSystemBase =
    val current = _impl
    if current != null then
      current
    else
      // First touch: trigger the platform-specific FileSystemInit, which writes back via
      // setImplementation as part of its own static initializer. Lazy (rather than in the
      // object body) so a partly-initialized platform object can't observe FileSystem
      // mid-bootstrap.
      FileSystemInit.init()
      val after = _impl
      if after == null then
        throw IllegalStateException(
          "FileSystem not initialized. Platform-specific initialization required."
        )
      after

  override def currentDirectory: IOPath = impl.currentDirectory
  override def homeDirectory: IOPath    = impl.homeDirectory
  override def tempDirectory: IOPath    = impl.tempDirectory
  override def isBrowser: Boolean       = impl.isBrowser
  override def isNode: Boolean          = impl.isNode

  override def exists(path: IOPath): Boolean      = impl.exists(path)
  override def isFile(path: IOPath): Boolean      = impl.isFile(path)
  override def isDirectory(path: IOPath): Boolean = impl.isDirectory(path)
  override def info(path: IOPath): FileInfo       = impl.info(path)

  override def readString(path: IOPath): String     = impl.readString(path)
  override def readBytes(path: IOPath): Array[Byte] = impl.readBytes(path)
  override def readLines(path: IOPath): Seq[String] = impl.readLines(path)

  override def readChunks(path: IOPath, chunkSize: Int): Iterator[Array[Byte]] = impl.readChunks(
    path,
    chunkSize
  )

  override def readStream(path: IOPath): InputStream                    = impl.readStream(path)
  override def writeStream(path: IOPath, mode: WriteMode): OutputStream = impl.writeStream(
    path,
    mode
  )

  override def writeString(path: IOPath, content: String, mode: WriteMode): Unit = impl.writeString(
    path,
    content,
    mode
  )

  override def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode): Unit = impl
    .writeBytes(path, content, mode)

  override def list(path: IOPath, options: ListOptions): Seq[IOPath] = impl.list(path, options)
  override def createDirectory(path: IOPath): Unit                   = impl.createDirectory(path)

  override def delete(path: IOPath): Boolean            = impl.delete(path)
  override def deleteRecursively(path: IOPath): Boolean = impl.deleteRecursively(path)

  override def copy(source: IOPath, target: IOPath, options: CopyOptions): Unit = impl.copy(
    source,
    target,
    options
  )

  override def move(source: IOPath, target: IOPath, overwrite: Boolean): Unit = impl.move(
    source,
    target,
    overwrite
  )

  override def createTempFile(prefix: String, suffix: String, directory: Option[IOPath]): IOPath =
    impl.createTempFile(prefix, suffix, directory)

  override def createTempDirectory(prefix: String, directory: Option[IOPath]): IOPath = impl
    .createTempDirectory(prefix, directory)

  override def readStringAsync(path: IOPath): Future[String]     = impl.readStringAsync(path)
  override def readBytesAsync(path: IOPath): Future[Array[Byte]] = impl.readBytesAsync(path)
  override def writeStringAsync(path: IOPath, content: String, mode: WriteMode): Future[Unit] = impl
    .writeStringAsync(path, content, mode)

  override def writeBytesAsync(path: IOPath, content: Array[Byte], mode: WriteMode): Future[Unit] =
    impl.writeBytesAsync(path, content, mode)

  override def listAsync(path: IOPath, options: ListOptions): Future[Seq[IOPath]] = impl.listAsync(
    path,
    options
  )

  override def infoAsync(path: IOPath): Future[FileInfo]  = impl.infoAsync(path)
  override def existsAsync(path: IOPath): Future[Boolean] = impl.existsAsync(path)

  override def createSymlink(link: IOPath, target: IOPath): Unit = impl.createSymlink(link, target)
  override def readSymlink(link: IOPath): IOPath                 = impl.readSymlink(link)

  override def permissions(path: IOPath): PermSet                       = impl.permissions(path)
  override def setPermissions(path: IOPath, permissions: PermSet): Unit = impl.setPermissions(
    path,
    permissions
  )

  override def owner(path: IOPath): String = impl.owner(path)
  override def group(path: IOPath): String = impl.group(path)

end FileSystem
