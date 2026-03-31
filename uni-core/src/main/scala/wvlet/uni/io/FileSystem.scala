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

  /**
    * Returns true if the path is a regular file.
    */
  def isFile(path: IOPath): Boolean

  /**
    * Returns true if the path is a directory.
    */
  def isDirectory(path: IOPath): Boolean

  /**
    * Returns file information for the given path.
    */
  def info(path: IOPath): FileInfo

  // ============================================================
  // Synchronous read operations
  // ============================================================

  /**
    * Reads the entire file as a string (UTF-8 encoding).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readString(path: IOPath): String

  /**
    * Reads the entire file as a byte array.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readBytes(path: IOPath): Array[Byte]

  /**
    * Reads the file line by line.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readLines(path: IOPath): Seq[String]

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

  /**
    * Opens a file and returns an InputStream for streaming reads. The caller is responsible for
    * closing the stream.
    *
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def readStream(path: IOPath): InputStream

  /**
    * Opens a file and returns an OutputStream for streaming writes. The caller is responsible for
    * closing the stream.
    *
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeStream(path: IOPath, mode: WriteMode = WriteMode.Create): OutputStream

  // ============================================================
  // Synchronous write operations
  // ============================================================

  /**
    * Writes a string to a file (UTF-8 encoding).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeString(path: IOPath, content: String, mode: WriteMode = WriteMode.Create): Unit

  /**
    * Writes a byte array to a file.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode = WriteMode.Create): Unit

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

  // ============================================================
  // Directory operations
  // ============================================================

  /**
    * Lists the contents of a directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def list(path: IOPath, options: ListOptions = ListOptions.default): Seq[IOPath]

  /**
    * Creates a directory (and parent directories if needed).
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createDirectory(path: IOPath): Unit

  /**
    * Creates a directory only if it doesn't exist.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def createDirectoryIfNotExists(path: IOPath): Unit =
    if !exists(path) then
      createDirectory(path)

  // ============================================================
  // Delete operations
  // ============================================================

  /**
    * Deletes a file or empty directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def delete(path: IOPath): Boolean

  /**
    * Deletes a file or directory recursively.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def deleteRecursively(path: IOPath): Boolean

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

  // ============================================================
  // Copy and move operations
  // ============================================================

  /**
    * Copies a file or directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def copy(source: IOPath, target: IOPath, options: CopyOptions = CopyOptions.default): Unit

  /**
    * Moves (renames) a file or directory.
    * @throws UnsupportedOperationException
    *   in browser environments
    */
  def move(source: IOPath, target: IOPath, overwrite: Boolean = false): Unit

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

  /**
    * Reads the entire file as a byte array asynchronously.
    */
  def readBytesAsync(path: IOPath): Future[Array[Byte]]

  /**
    * Writes a string to a file asynchronously.
    */
  def writeStringAsync(
      path: IOPath,
      content: String,
      mode: WriteMode = WriteMode.Create
  ): Future[Unit]

  /**
    * Writes a byte array to a file asynchronously.
    */
  def writeBytesAsync(
      path: IOPath,
      content: Array[Byte],
      mode: WriteMode = WriteMode.Create
  ): Future[Unit]

  /**
    * Lists the contents of a directory asynchronously.
    */
  def listAsync(path: IOPath, options: ListOptions = ListOptions.default): Future[Seq[IOPath]]

  /**
    * Returns file information asynchronously.
    */
  def infoAsync(path: IOPath): Future[FileInfo]

  /**
    * Checks if a path exists asynchronously.
    */
  def existsAsync(path: IOPath): Future[Boolean]

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
    if _impl == null then
      throw IllegalStateException(
        "FileSystem not initialized. Platform-specific initialization required."
      )
    _impl

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

end FileSystem
