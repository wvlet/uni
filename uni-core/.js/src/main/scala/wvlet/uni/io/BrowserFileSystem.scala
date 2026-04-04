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

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array
import java.time.Instant

/**
  * Browser file system implementation. Uses an in-memory file system as the default storage, with
  * optional integration with the File System Access API for modern browsers.
  *
  * The in-memory file system is useful for:
  *   - Running tests in browser environment
  *   - Temporary file storage during a session
  *   - Sandboxed file operations
  *
  * For persistent storage in browsers, consider using:
  *   - File System Access API (requires user interaction)
  *   - IndexedDB (for larger data)
  *   - LocalStorage (for small text data)
  */
private[io] object BrowserFileSystem:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  // In-memory file system storage
  private case class MemoryFile(
      content: Array[Byte],
      isDirectory: Boolean,
      createdAt: Instant,
      modifiedAt: Instant
  )

  private val storage: mutable.Map[String, MemoryFile] = mutable.Map.empty
  private val symlinks: mutable.Map[String, String]    = mutable.Map.empty

  // Initialize root directory
  storage("/") = MemoryFile(Array.emptyByteArray, isDirectory = true, Instant.now(), Instant.now())
  storage("/home") = MemoryFile(
    Array.emptyByteArray,
    isDirectory = true,
    Instant.now(),
    Instant.now()
  )

  storage("/tmp") = MemoryFile(
    Array.emptyByteArray,
    isDirectory = true,
    Instant.now(),
    Instant.now()
  )

  private def normalizePath(path: IOPath): String =
    val p = path.posixPath
    if p.isEmpty then
      "/"
    else
      p

  // ============================================================
  // Sync operations
  // ============================================================

  def exists(path: IOPath): Boolean =
    val key = normalizePath(path)
    storage.contains(key) || symlinks.contains(key)

  def isFile(path: IOPath): Boolean = storage.get(normalizePath(path)).exists(!_.isDirectory)

  def isDirectory(path: IOPath): Boolean = storage.get(normalizePath(path)).exists(_.isDirectory)

  def info(path: IOPath): FileInfo =
    val key = normalizePath(path)
    if symlinks.contains(key) then
      FileInfo(
        path = path,
        fileType = FileType.SymbolicLink,
        size = 0L,
        lastModified = None,
        isReadable = true,
        isWritable = true,
        isExecutable = false,
        isHidden = path.fileName.startsWith(".")
      )
    else
      storage.get(key) match
        case Some(file) =>
          FileInfo(
            path = path,
            fileType =
              if file.isDirectory then
                FileType.Directory
              else
                FileType.File
            ,
            size = file.content.length.toLong,
            lastModified = Some(file.modifiedAt),
            createdAt = Some(file.createdAt),
            isReadable = true,
            isWritable = true,
            isExecutable = false,
            isHidden = path.fileName.startsWith(".")
          )
        case None =>
          FileInfo.notFound(path)
    end if

  end info

  def readString(path: IOPath): String =
    val key = normalizePath(path)
    storage.get(key) match
      case Some(file) if !file.isDirectory =>
        new String(file.content, "UTF-8")
      case Some(_) =>
        throw IOOperationException(s"Cannot read directory: ${path}")
      case None =>
        throw NoSuchFileException(s"File not found: ${path}")

  def readBytes(path: IOPath): Array[Byte] =
    val key = normalizePath(path)
    storage.get(key) match
      case Some(file) if !file.isDirectory =>
        file.content.clone()
      case Some(_) =>
        throw IOOperationException(s"Cannot read directory: ${path}")
      case None =>
        throw NoSuchFileException(s"File not found: ${path}")

  def writeString(path: IOPath, content: String, mode: WriteMode): Unit = writeBytes(
    path,
    content.getBytes("UTF-8"),
    mode
  )

  def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode): Unit =
    val key = normalizePath(path)
    val now = Instant.now()

    // Create parent directories
    path
      .parent
      .foreach { parent =>
        if !exists(parent) then
          createDirectory(parent)
      }

    mode match
      case WriteMode.CreateNew =>
        if storage.contains(key) then
          throw FileAlreadyExistsException(path.path)
        storage(key) = MemoryFile(content.clone(), isDirectory = false, now, now)

      case WriteMode.Create =>
        storage.get(key) match
          case Some(existing) if !existing.isDirectory =>
            storage(key) = MemoryFile(content.clone(), isDirectory = false, existing.createdAt, now)
          case Some(_) =>
            throw IOOperationException(s"Cannot write to directory: ${path}")
          case None =>
            storage(key) = MemoryFile(content.clone(), isDirectory = false, now, now)

      case WriteMode.Append =>
        storage.get(key) match
          case Some(existing) if !existing.isDirectory =>
            val newContent = existing.content ++ content
            storage(key) = MemoryFile(newContent, isDirectory = false, existing.createdAt, now)
          case Some(_) =>
            throw IOOperationException(s"Cannot write to directory: ${path}")
          case None =>
            storage(key) = MemoryFile(content.clone(), isDirectory = false, now, now)

  end writeBytes

  def list(path: IOPath, options: ListOptions): Seq[IOPath] =
    val key = normalizePath(path)
    if !isDirectory(path) then
      Seq.empty
    else
      val prefix =
        if key == "/" then
          "/"
        else
          key + "/"
      var result = storage
        .keys
        .filter { k =>
          k.startsWith(prefix) && k != key
        }
        .map { k =>
          // Get immediate children only (unless recursive)
          val relativePath = k.stripPrefix(prefix)
          val childName    =
            if options.recursive then
              relativePath
            else
              relativePath.takeWhile(_ != '/')
          prefix + childName
        }
        .toSet
        .toSeq
        .map(IOPath.parse)

      // Filter hidden files
      if !options.includeHidden then
        result = result.filterNot(_.fileName.startsWith("."))

      // Filter by depth for recursive listing
      if options.recursive then
        options
          .maxDepth
          .foreach { maxDepth =>
            result = result.filter { p =>
              val depth = p.segments.length - path.segments.length
              depth <= maxDepth
            }
          }

      // Filter by extension
      if options.extensions.nonEmpty then
        val exts = options.extensions.map(_.toLowerCase).toSet
        result = result.filter { p =>
          val ext = p.extension.toLowerCase
          ext.nonEmpty && exts.contains(ext)
        }

      result.sortBy(_.path)

    end if

  end list

  def createDirectory(path: IOPath): Unit =
    val key = normalizePath(path)
    val now = Instant.now()

    // Create parent directories first
    path
      .parent
      .foreach { parent =>
        if !exists(parent) then
          createDirectory(parent)
      }

    if !storage.contains(key) then
      storage(key) = MemoryFile(Array.emptyByteArray, isDirectory = true, now, now)
    else if !isDirectory(path) then
      throw IOOperationException(s"Path exists and is not a directory: ${path}")

  def delete(path: IOPath): Boolean =
    val key = normalizePath(path)
    storage.get(key) match
      case Some(file) if file.isDirectory =>
        // Check if directory is empty
        val prefix =
          if key == "/" then
            "/"
          else
            key + "/"
        val hasChildren = storage.keys.exists(k => k.startsWith(prefix) && k != key)
        if hasChildren then
          throw IOOperationException(s"Directory not empty: ${path}")
        storage.remove(key).isDefined
      case Some(_) =>
        storage.remove(key).isDefined
      case None =>
        false

  def deleteRecursively(path: IOPath): Boolean =
    val key = normalizePath(path)
    if !exists(path) then
      false
    else
      val prefix =
        if key == "/" then
          "/"
        else
          key + "/"
      // Delete all children first
      val children = storage.keys.filter(k => k.startsWith(prefix)).toSeq
      children.foreach(storage.remove)
      // Delete the path itself
      storage.remove(key).isDefined || children.nonEmpty

  def copy(source: IOPath, target: IOPath, options: CopyOptions): Unit =
    val sourceKey = normalizePath(source)
    val targetKey = normalizePath(target)
    val now       = Instant.now()

    storage.get(sourceKey) match
      case None =>
        throw NoSuchFileException(s"Source not found: ${source}")
      case Some(sourceFile) if sourceFile.isDirectory && options.recursive =>
        // Copy directory recursively
        val sourcePrefix =
          if sourceKey == "/" then
            "/"
          else
            sourceKey + "/"
        val targetPrefix =
          if targetKey == "/" then
            "/"
          else
            targetKey + "/"

        // Create target directory
        createDirectory(target)

        // Copy all children
        storage
          .toSeq
          .foreach { (key, file) =>
            if key.startsWith(sourcePrefix) then
              val relativePath = key.stripPrefix(sourcePrefix)
              val newKey       = targetPrefix + relativePath
              val newFile      =
                if options.preserveAttributes then
                  file.copy(modifiedAt = now)
                else
                  MemoryFile(file.content.clone(), file.isDirectory, now, now)

              if !options.overwrite && storage.contains(newKey) then
                throw FileAlreadyExistsException(newKey)
              storage(newKey) = newFile
          }
      case Some(sourceFile) =>
        // Copy single file
        target.parent.foreach(createDirectory)

        if !options.overwrite && storage.contains(targetKey) then
          throw FileAlreadyExistsException(target.path)

        val newFile =
          if options.preserveAttributes then
            sourceFile.copy(modifiedAt = now)
          else
            MemoryFile(sourceFile.content.clone(), sourceFile.isDirectory, now, now)
        storage(targetKey) = newFile

    end match

  end copy

  def move(source: IOPath, target: IOPath, overwrite: Boolean): Unit =
    val sourceKey = normalizePath(source)
    val targetKey = normalizePath(target)

    if !exists(source) then
      throw NoSuchFileException(s"Source not found: ${source}")
    if !overwrite && exists(target) then
      throw FileAlreadyExistsException(target.path)

    // Create target parent directory
    target.parent.foreach(createDirectory)

    if isDirectory(source) then
      // Move directory and all contents
      val sourcePrefix =
        if sourceKey == "/" then
          "/"
        else
          sourceKey + "/"
      val targetPrefix =
        if targetKey == "/" then
          "/"
        else
          targetKey + "/"

      val toMove = storage
        .toSeq
        .filter { (key, _) =>
          key == sourceKey || key.startsWith(sourcePrefix)
        }

      toMove.foreach { (key, file) =>
        storage.remove(key)
        val newKey =
          if key == sourceKey then
            targetKey
          else
            targetPrefix + key.stripPrefix(sourcePrefix)
        storage(newKey) = file
      }
    else
      // Move single file
      storage
        .remove(sourceKey)
        .foreach { file =>
          storage(targetKey) = file
        }

    end if

  end move

  private var tempCounter = 0L

  def createTempFile(prefix: String, suffix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.getOrElse(IOPath.parse("/tmp"))
    createDirectory(dir)
    tempCounter += 1
    val fileName = s"${prefix}${tempCounter}${suffix}"
    val path     = dir.resolve(fileName)
    writeBytes(path, Array.emptyByteArray, WriteMode.CreateNew)
    path

  def createTempDirectory(prefix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.getOrElse(IOPath.parse("/tmp"))
    createDirectory(dir)
    tempCounter += 1
    val dirName = s"${prefix}${tempCounter}"
    val path    = dir.resolve(dirName)
    createDirectory(path)
    path

  // ============================================================
  // Async operations
  // ============================================================

  def readStringAsync(path: IOPath): Future[String] = Future(readString(path))

  def readBytesAsync(path: IOPath): Future[Array[Byte]] = Future(readBytes(path))

  def writeStringAsync(path: IOPath, content: String, mode: WriteMode): Future[Unit] = Future(
    writeString(path, content, mode)
  )

  def writeBytesAsync(path: IOPath, content: Array[Byte], mode: WriteMode): Future[Unit] = Future(
    writeBytes(path, content, mode)
  )

  def listAsync(path: IOPath, options: ListOptions): Future[Seq[IOPath]] = Future(
    list(path, options)
  )

  def infoAsync(path: IOPath): Future[FileInfo] = Future(info(path))

  def existsAsync(path: IOPath): Future[Boolean] = Future(exists(path))

  def createSymlink(link: IOPath, target: IOPath): Unit =
    symlinks(normalizePath(link)) = normalizePath(target)

  def readSymlink(link: IOPath): IOPath =
    val key = normalizePath(link)
    symlinks.get(key) match
      case Some(target) =>
        IOPath.parse(target)
      case None =>
        throw NoSuchFileException(s"Not a symlink: ${link}")

  // ============================================================
  // Browser File System Access API integration
  // ============================================================

  /**
    * Checks if the File System Access API is available.
    */
  def isFileSystemAccessApiAvailable: Boolean =
    js.typeOf(g.window) != "undefined" && !js.isUndefined(g.window.showOpenFilePicker)

  /**
    * Opens a file picker dialog and reads the selected file. Requires user interaction (click
    * event).
    */
  def pickAndReadFile(): Future[Option[(String, Array[Byte])]] =
    if !isFileSystemAccessApiAvailable then
      Future.successful(None)
    else
      val promise = Promise[Option[(String, Array[Byte])]]()
      val picker  = g.window.showOpenFilePicker().asInstanceOf[js.Promise[js.Array[js.Dynamic]]]

      picker.`then`[Unit](
        { (handles: js.Array[js.Dynamic]) =>
          if handles.length > 0 then
            val handle = handles(0)
            handle
              .getFile()
              .asInstanceOf[js.Promise[js.Dynamic]]
              .`then`[Unit](
                { (file: js.Dynamic) =>
                  file
                    .arrayBuffer()
                    .asInstanceOf[js.Promise[ArrayBuffer]]
                    .`then`[Unit](
                      { (buffer: ArrayBuffer) =>
                        val arr = new Int8Array(buffer).toArray
                        promise.success(Some((file.name.asInstanceOf[String], arr)))
                        ()
                      },
                      { (error: Any) =>
                        promise.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
                        ()
                      }
                    )
                  ()
                },
                { (error: Any) =>
                  promise.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
                  ()
                }
              )
          else
            promise.success(None)
          end if
          ()
        },
        { (error: Any) =>
          // User cancelled or error
          promise.success(None)
          ()
        }
      )
      promise.future

  /**
    * Opens a save file dialog and writes content. Requires user interaction (click event).
    */
  def pickAndSaveFile(suggestedName: String, content: Array[Byte]): Future[Boolean] =
    if !isFileSystemAccessApiAvailable then
      Future.successful(false)
    else
      val promise = Promise[Boolean]()
      val options = js.Dynamic.literal(suggestedName = suggestedName)
      val picker  = g.window.showSaveFilePicker(options).asInstanceOf[js.Promise[js.Dynamic]]

      picker.`then`[Unit](
        { (handle: js.Dynamic) =>
          handle
            .createWritable()
            .asInstanceOf[js.Promise[js.Dynamic]]
            .`then`[Unit](
              { (writable: js.Dynamic) =>
                val buffer = new Uint8Array(content.length)
                var i      = 0
                while i < content.length do
                  buffer(i) = (content(i) & 0xff).toShort
                  i += 1
                writable
                  .write(buffer)
                  .asInstanceOf[js.Promise[Unit]]
                  .`then`[Unit](
                    { (_: Unit) =>
                      writable
                        .close()
                        .asInstanceOf[js.Promise[Unit]]
                        .`then`[Unit](
                          { (_: Unit) =>
                            promise.success(true)
                            ()
                          },
                          { (error: Any) =>
                            promise.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
                            ()
                          }
                        )
                      ()
                    },
                    { (error: Any) =>
                      promise.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
                      ()
                    }
                  )
                ()
              },
              { (error: Any) =>
                promise.failure(js.JavaScriptException(error.asInstanceOf[js.Any]))
                ()
              }
            )
          ()
        },
        { (error: Any) =>
          // User cancelled
          promise.success(false)
          ()
        }
      )
      promise.future

  /**
    * Clears all files from the in-memory file system (useful for testing).
    */
  def clear(): Unit =
    storage.clear()
    symlinks.clear()
    val now = Instant.now()
    storage("/") = MemoryFile(Array.emptyByteArray, isDirectory = true, now, now)
    storage("/home") = MemoryFile(Array.emptyByteArray, isDirectory = true, now, now)
    storage("/tmp") = MemoryFile(Array.emptyByteArray, isDirectory = true, now, now)

end BrowserFileSystem
