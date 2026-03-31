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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import java.time.Instant

/**
  * Scala.js FileSystem implementation supporting both Node.js and browser environments.
  */
private[io] object FileSystemJS extends FileSystemBase:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  // Detect environment
  private def isNodeEnv: Boolean =
    js.typeOf(g.process) != "undefined" && !js.isUndefined(g.process.versions) &&
      !js.isUndefined(g.process.versions.node)

  private def isBrowserEnv: Boolean =
    js.typeOf(g.window) != "undefined" && js.typeOf(g.document) != "undefined"

  // Initialize platform settings
  if isNodeEnv then
    IOPath.separator = NodePathModule.sep
    IOPath.isWindows = NodeOSModule.platform() == "win32"
  else
    IOPath.separator = "/"
    IOPath.isWindows = false

  override def isBrowser: Boolean = isBrowserEnv
  override def isNode: Boolean    = isNodeEnv

  override def currentDirectory: IOPath =
    if isNodeEnv then
      IOPath.parse(g.process.cwd().asInstanceOf[String])
    else
      IOPath.parse("/")

  override def homeDirectory: IOPath =
    if isNodeEnv then
      IOPath.parse(NodeOSModule.homedir())
    else
      IOPath.parse("/home")

  override def tempDirectory: IOPath =
    if isNodeEnv then
      IOPath.parse(NodeOSModule.tmpdir())
    else
      IOPath.parse("/tmp")

  // ============================================================
  // Sync operations (Node.js only)
  // ============================================================

  private def requireNode(operation: String): Nothing =
    throw UnsupportedOperationException(
      s"${operation} is not supported in browser environment. Use async API instead."
    )

  override def exists(path: IOPath): Boolean =
    if isNodeEnv then
      try
        NodeFSModule.statSync(path.path)
        true
      catch
        case _: Throwable =>
          false
    else if isBrowserEnv then
      // Browser: check in-memory storage
      BrowserFileSystem.exists(path)
    else
      false

  override def isFile(path: IOPath): Boolean =
    if isNodeEnv then
      try
        NodeFSModule.statSync(path.path).isFile()
      catch
        case _: Throwable =>
          false
    else if isBrowserEnv then
      BrowserFileSystem.isFile(path)
    else
      false

  override def isDirectory(path: IOPath): Boolean =
    if isNodeEnv then
      try
        NodeFSModule.statSync(path.path).isDirectory()
      catch
        case _: Throwable =>
          false
    else if isBrowserEnv then
      BrowserFileSystem.isDirectory(path)
    else
      false

  override def info(path: IOPath): FileInfo =
    if isNodeEnv then
      try
        val stats    = NodeFSModule.lstatSync(path.path)
        val fileType =
          if stats.isFile() then
            FileType.File
          else if stats.isDirectory() then
            FileType.Directory
          else if stats.isSymbolicLink() then
            FileType.SymbolicLink
          else
            FileType.Other

        FileInfo(
          path = path,
          fileType = fileType,
          size = stats.size.toLong,
          lastModified = Some(Instant.ofEpochMilli(stats.mtimeMs.toLong)),
          lastAccessed = Some(Instant.ofEpochMilli(stats.atimeMs.toLong)),
          createdAt = Some(Instant.ofEpochMilli(stats.birthtimeMs.toLong)),
          isReadable = true, // Node.js requires separate access check
          isWritable = true,
          isExecutable = (stats.mode & 0x49) != 0, // Check executable bits
          isHidden = path.fileName.startsWith(".")
        )
      catch
        case _: Throwable =>
          FileInfo.notFound(path)
    else if isBrowserEnv then
      BrowserFileSystem.info(path)
    else
      FileInfo.notFound(path)

  override def readString(path: IOPath): String =
    if isNodeEnv then
      NodeFSModule.readFileSync(path.path, "utf8")
    else if isBrowserEnv then
      BrowserFileSystem.readString(path)
    else
      requireNode("readString")

  override def readBytes(path: IOPath): Array[Byte] =
    if isNodeEnv then
      val buffer = NodeFSModule.readFileSync(path.path)
      uint8ArrayToByteArray(buffer)
    else if isBrowserEnv then
      BrowserFileSystem.readBytes(path)
    else
      requireNode("readBytes")

  override def readLines(path: IOPath): Seq[String] = readString(path).split("\n").toSeq

  override def readLinesLazy(path: IOPath): Iterator[String] =
    // Node.js readFileSync is inherently eager; split into iterator
    readString(path).linesIterator

  override def readChunks(path: IOPath, chunkSize: Int): Iterator[Array[Byte]] = readBytes(path)
    .grouped(chunkSize)

  override def readStream(path: IOPath): InputStream =
    if isNodeEnv then
      ByteArrayInputStream(readBytes(path))
    else if isBrowserEnv then
      ByteArrayInputStream(BrowserFileSystem.readBytes(path))
    else
      requireNode("readStream")

  override def writeStream(path: IOPath, mode: WriteMode): OutputStream =
    if isNodeEnv || isBrowserEnv then
      FlushToFileOutputStream(path, mode)
    else
      requireNode("writeStream")

  private def withEEXISTHandler[A](path: IOPath)(body: => A): A =
    try
      body
    catch
      case e: js.JavaScriptException if isEEXIST(e) =>
        throw FileAlreadyExistsException(path.path)

  override def writeString(path: IOPath, content: String, mode: WriteMode): Unit =
    if isNodeEnv then
      ensureParentDirectory(path)
      withEEXISTHandler(path) {
        mode match
          case WriteMode.CreateNew =>
            val options = js.Dynamic.literal(flag = "wx", encoding = "utf8")
            NodeFSModule.writeFileSync(path.path, content, options.asInstanceOf[js.Object])
          case WriteMode.Create =>
            val options = js.Dynamic.literal(flag = "w", encoding = "utf8")
            NodeFSModule.writeFileSync(path.path, content, options.asInstanceOf[js.Object])
          case WriteMode.Append =>
            NodeFSModule.appendFileSync(path.path, content)
      }
    else if isBrowserEnv then
      BrowserFileSystem.writeString(path, content, mode)
    else
      requireNode("writeString")

  override def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode): Unit =
    if isNodeEnv then
      ensureParentDirectory(path)
      val buffer = byteArrayToUint8Array(content)
      withEEXISTHandler(path) {
        mode match
          case WriteMode.CreateNew =>
            val options = js.Dynamic.literal(flag = "wx")
            NodeFSModule.writeFileSync(path.path, buffer, options.asInstanceOf[js.Object])
          case WriteMode.Create =>
            val options = js.Dynamic.literal(flag = "w")
            NodeFSModule.writeFileSync(path.path, buffer, options.asInstanceOf[js.Object])
          case WriteMode.Append =>
            NodeFSModule.appendFileSync(path.path, buffer)
      }
    else if isBrowserEnv then
      BrowserFileSystem.writeBytes(path, content, mode)
    else
      requireNode("writeBytes")

  private def ensureParentDirectory(path: IOPath): Unit = path
    .parent
    .foreach { parent =>
      if !exists(parent) then
        createDirectory(parent)
    }

  override def list(path: IOPath, options: ListOptions): Seq[IOPath] =
    if isNodeEnv then
      if !isDirectory(path) then
        Seq.empty
      else
        val dirOptions = js.Dynamic.literal(withFileTypes = true)
        val entries    = NodeFSModule.readdirSync(path.path, dirOptions.asInstanceOf[js.Object])

        var result = entries
          .toSeq
          .map { entry =>
            val dirent = entry.asInstanceOf[NodeDirent]
            path.resolve(dirent.name)
          }

        // Filter hidden files
        if !options.includeHidden then
          result = result.filterNot(_.fileName.startsWith("."))

        // Recursive listing
        if options.recursive then
          val maxDepth = options.maxDepth.getOrElse(Int.MaxValue)
          result = listRecursive(path, result, 1, maxDepth, options)

        // Filter by extension
        if options.extensions.nonEmpty then
          val exts = options.extensions.map(_.toLowerCase).toSet
          result = result.filter { p =>
            val ext = p.extension.toLowerCase
            ext.nonEmpty && exts.contains(ext)
          }

        // Filter by glob pattern
        options
          .glob
          .foreach { pattern =>
            val regex = ListOptions.globToRegex(pattern)
            result = result.filter { p =>
              regex.matches(p.path) || regex.matches(p.fileName)
            }
          }

        result
    else if isBrowserEnv then
      BrowserFileSystem.list(path, options)
    else
      requireNode("list")

  private def listRecursive(
      base: IOPath,
      current: Seq[IOPath],
      depth: Int,
      maxDepth: Int,
      options: ListOptions
  ): Seq[IOPath] =
    if depth >= maxDepth then
      current
    else
      val subdirs    = current.filter(isDirectory)
      val subentries = subdirs.flatMap { dir =>
        val dirOptions = js.Dynamic.literal(withFileTypes = true)
        try
          val entries = NodeFSModule.readdirSync(dir.path, dirOptions.asInstanceOf[js.Object])
          entries
            .toSeq
            .map { entry =>
              val dirent = entry.asInstanceOf[NodeDirent]
              dir.resolve(dirent.name)
            }
            .filterNot(p => !options.includeHidden && p.fileName.startsWith("."))
        catch
          case _: Throwable =>
            Seq.empty
      }
      if subentries.isEmpty then
        current
      else
        current ++ listRecursive(base, subentries, depth + 1, maxDepth, options)

  override def createDirectory(path: IOPath): Unit =
    if isNodeEnv then
      val options = js.Dynamic.literal(recursive = true)
      NodeFSModule.mkdirSync(path.path, options.asInstanceOf[js.Object])
    else if isBrowserEnv then
      BrowserFileSystem.createDirectory(path)
    else
      requireNode("createDirectory")

  override def delete(path: IOPath): Boolean =
    if isNodeEnv then
      try
        if isDirectory(path) then
          val options = js.Dynamic.literal()
          NodeFSModule.rmdirSync(path.path, options.asInstanceOf[js.Object])
        else
          NodeFSModule.unlinkSync(path.path)
        true
      catch
        case _: Throwable =>
          false
    else if isBrowserEnv then
      BrowserFileSystem.delete(path)
    else
      requireNode("delete")

  override def deleteRecursively(path: IOPath): Boolean =
    if isNodeEnv then
      try
        if isDirectory(path) then
          val options = js.Dynamic.literal(recursive = true, force = true)
          NodeFSModule.rmdirSync(path.path, options.asInstanceOf[js.Object])
        else
          NodeFSModule.unlinkSync(path.path)
        true
      catch
        case _: Throwable =>
          false
    else if isBrowserEnv then
      BrowserFileSystem.deleteRecursively(path)
    else
      requireNode("deleteRecursively")

  override def copy(source: IOPath, target: IOPath, options: CopyOptions): Unit =
    if isNodeEnv then
      ensureParentDirectory(target)
      if isDirectory(source) && options.recursive then
        copyDirectoryRecursive(source, target, options)
      else
        val mode =
          if options.overwrite then
            0
          else
            1 // COPYFILE_EXCL = 1
        NodeFSModule.copyFileSync(source.path, target.path, mode)
    else if isBrowserEnv then
      BrowserFileSystem.copy(source, target, options)
    else
      requireNode("copy")

  private def copyDirectoryRecursive(source: IOPath, target: IOPath, options: CopyOptions): Unit =
    createDirectory(target)
    val entries = list(source, ListOptions(includeHidden = true))
    entries.foreach { entry =>
      val relativePath = entry.relativeTo(source)
      val targetEntry  = target.resolve(relativePath)
      if isDirectory(entry) then
        copyDirectoryRecursive(entry, targetEntry, options)
      else
        val mode =
          if options.overwrite then
            0
          else
            1
        NodeFSModule.copyFileSync(entry.path, targetEntry.path, mode)
    }

  override def move(source: IOPath, target: IOPath, overwrite: Boolean): Unit =
    if isNodeEnv then
      if !overwrite && exists(target) then
        throw FileAlreadyExistsException(target.path)
      ensureParentDirectory(target)
      NodeFSModule.renameSync(source.path, target.path)
    else if isBrowserEnv then
      BrowserFileSystem.move(source, target, overwrite)
    else
      requireNode("move")

  override def createTempFile(prefix: String, suffix: String, directory: Option[IOPath]): IOPath =
    if isNodeEnv then
      val dir = directory.getOrElse(tempDirectory)
      // Generate random file names and try to create atomically
      var attempts = 0
      while attempts < 100 do
        val randomPart = scala.util.Random.alphanumeric.take(8).mkString
        val tempPath   = dir.resolve(s"${prefix}${randomPart}${suffix}")
        try
          writeString(tempPath, "", WriteMode.CreateNew)
          return tempPath
        catch
          case _: FileAlreadyExistsException =>
          // File exists, try again with different random name
        attempts += 1
      throw IOOperationException("Failed to create temporary file after 100 attempts")
    else if isBrowserEnv then
      BrowserFileSystem.createTempFile(prefix, suffix, directory)
    else
      requireNode("createTempFile")

  override def createTempDirectory(prefix: String, directory: Option[IOPath]): IOPath =
    if isNodeEnv then
      val dir = directory.map(_.path).getOrElse(NodeOSModule.tmpdir())
      IOPath.parse(NodeFSModule.mkdtempSync(s"${dir}/${prefix}"))
    else if isBrowserEnv then
      BrowserFileSystem.createTempDirectory(prefix, directory)
    else
      requireNode("createTempDirectory")

  // ============================================================
  // Async operations
  // ============================================================

  override def readStringAsync(path: IOPath): Future[String] =
    if isNodeEnv then
      promiseToFuture(NodeFSModule.promises.readFile(path.path, "utf8"))
    else if isBrowserEnv then
      BrowserFileSystem.readStringAsync(path)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  override def readBytesAsync(path: IOPath): Future[Array[Byte]] =
    if isNodeEnv then
      promiseToFuture(NodeFSModule.promises.readFile(path.path)).map(uint8ArrayToByteArray)
    else if isBrowserEnv then
      BrowserFileSystem.readBytesAsync(path)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  override def writeStringAsync(path: IOPath, content: String, mode: WriteMode): Future[Unit] =
    if isNodeEnv then
      ensureParentDirectoryAsync(path).flatMap { _ =>
        val flag =
          mode match
            case WriteMode.CreateNew =>
              "wx"
            case WriteMode.Create =>
              "w"
            case WriteMode.Append =>
              "a"
        val options = js.Dynamic.literal(flag = flag, encoding = "utf8")
        promiseToFuture(
          NodeFSModule.promises.writeFile(path.path, content, options.asInstanceOf[js.Object])
        )
      }
    else if isBrowserEnv then
      BrowserFileSystem.writeStringAsync(path, content, mode)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  override def writeBytesAsync(path: IOPath, content: Array[Byte], mode: WriteMode): Future[Unit] =
    if isNodeEnv then
      ensureParentDirectoryAsync(path).flatMap { _ =>
        val flag =
          mode match
            case WriteMode.CreateNew =>
              "wx"
            case WriteMode.Create =>
              "w"
            case WriteMode.Append =>
              "a"
        val buffer  = byteArrayToUint8Array(content)
        val options = js.Dynamic.literal(flag = flag)
        promiseToFuture(
          NodeFSModule.promises.writeFile(path.path, buffer, options.asInstanceOf[js.Object])
        )
      }
    else if isBrowserEnv then
      BrowserFileSystem.writeBytesAsync(path, content, mode)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  private def ensureParentDirectoryAsync(path: IOPath): Future[Unit] =
    path.parent match
      case Some(parent) =>
        existsAsync(parent).flatMap { exists =>
          if exists then
            Future.successful(())
          else
            val options = js.Dynamic.literal(recursive = true)
            promiseToFuture(
              NodeFSModule.promises.mkdir(parent.path, options.asInstanceOf[js.Object])
            )
        }
      case None =>
        Future.successful(())

  override def listAsync(path: IOPath, options: ListOptions): Future[Seq[IOPath]] =
    if isNodeEnv then
      val dirOptions = js.Dynamic.literal(withFileTypes = true)
      promiseToFuture(NodeFSModule.promises.readdir(path.path, dirOptions.asInstanceOf[js.Object]))
        .map { entries =>
          var result = entries
            .toSeq
            .map { entry =>
              val dirent = entry.asInstanceOf[NodeDirent]
              path.resolve(dirent.name)
            }

          if !options.includeHidden then
            result = result.filterNot(_.fileName.startsWith("."))

          if options.extensions.nonEmpty then
            val exts = options.extensions.map(_.toLowerCase).toSet
            result = result.filter { p =>
              val ext = p.extension.toLowerCase
              ext.nonEmpty && exts.contains(ext)
            }

          result
        }
    else if isBrowserEnv then
      BrowserFileSystem.listAsync(path, options)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  override def infoAsync(path: IOPath): Future[FileInfo] =
    if isNodeEnv then
      promiseToFuture(NodeFSModule.promises.stat(path.path))
        .map { stats =>
          val fileType =
            if stats.isFile() then
              FileType.File
            else if stats.isDirectory() then
              FileType.Directory
            else if stats.isSymbolicLink() then
              FileType.SymbolicLink
            else
              FileType.Other

          FileInfo(
            path = path,
            fileType = fileType,
            size = stats.size.toLong,
            lastModified = Some(Instant.ofEpochMilli(stats.mtimeMs.toLong)),
            lastAccessed = Some(Instant.ofEpochMilli(stats.atimeMs.toLong)),
            createdAt = Some(Instant.ofEpochMilli(stats.birthtimeMs.toLong)),
            isReadable = true,
            isWritable = true,
            isExecutable = (stats.mode & 0x49) != 0,
            isHidden = path.fileName.startsWith(".")
          )
        }
        .recover { case _: Throwable =>
          FileInfo.notFound(path)
        }
    else if isBrowserEnv then
      BrowserFileSystem.infoAsync(path)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  override def existsAsync(path: IOPath): Future[Boolean] =
    if isNodeEnv then
      promiseToFuture(NodeFSModule.promises.access(path.path))
        .map(_ => true)
        .recover { case _: Throwable =>
          false
        }
    else if isBrowserEnv then
      BrowserFileSystem.existsAsync(path)
    else
      Future.failed(UnsupportedOperationException("No file system available"))

  // ============================================================
  // Utility methods
  // ============================================================

  private def isEEXIST(e: js.JavaScriptException): Boolean =
    try
      val error = e.exception.asInstanceOf[js.Dynamic]
      error.code.asInstanceOf[String] == "EEXIST"
    catch
      case _: Throwable =>
        false

  private def promiseToFuture[T](promise: js.Promise[T]): Future[T] =
    val p = Promise[T]()
    promise.`then`[Unit](
      { (value: T) =>
        p.success(value);
        ()
      },
      { (error: Any) =>
        p.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
        ()
      }
    )
    p.future

  private def uint8ArrayToByteArray(arr: Uint8Array): Array[Byte] =
    val result = new Array[Byte](arr.length)
    var i      = 0
    while i < arr.length do
      result(i) = arr(i).toByte
      i += 1
    result

  private def byteArrayToUint8Array(arr: Array[Byte]): Uint8Array =
    val result = new Uint8Array(arr.length)
    var i      = 0
    while i < arr.length do
      result(i) = (arr(i) & 0xff).toShort
      i += 1
    result

  /**
    * An OutputStream that buffers writes in memory and flushes to the file system on close.
    */
  private class FlushToFileOutputStream(path: IOPath, mode: WriteMode)
      extends ByteArrayOutputStream:
    override def close(): Unit =
      val bytes  = toByteArray
      val buffer = byteArrayToUint8Array(bytes)
      mode match
        case WriteMode.Append =>
          NodeFSModule.appendFileSync(path.path, buffer)
        case _ =>
          FileSystemJS.writeBytes(path, bytes, mode)
      super.close()

  end FlushToFileOutputStream

end FileSystemJS

/**
  * Scala.js FileSystem initialization.
  */
object FileSystemInit:
  // Register JS implementation
  FileSystem.setImplementation(FileSystemJS)

  /**
    * Ensures the FileSystem is initialized.
    */
  def init(): Unit = ()

end FileSystemInit
