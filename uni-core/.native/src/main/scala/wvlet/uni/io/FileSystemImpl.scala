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

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import scalanative.posix.unistd
import scalanative.unsafe.*
import scalanative.unsigned.*

/**
  * Scala Native implementation of FileSystem. Uses java.io APIs which are well-supported in Scala
  * Native.
  */
private[io] object FileSystemNative extends FileSystemBase:
  // Initialize platform settings
  IOPath.separator = File.separator
  IOPath.isWindows = System.getProperty("os.name", "").toLowerCase.contains("win")

  private given ExecutionContext = ExecutionContext.global

  private def toJavaFile(path: IOPath): File = File(path.path)

  override def currentDirectory: IOPath = IOPath.parse(System.getProperty("user.dir", "."))

  override def homeDirectory: IOPath = IOPath.parse(System.getProperty("user.home", "."))

  override def tempDirectory: IOPath = IOPath.parse(System.getProperty("java.io.tmpdir", "/tmp"))

  override def isBrowser: Boolean = false
  override def isNode: Boolean    = false

  override def exists(path: IOPath): Boolean = toJavaFile(path).exists()

  override def isFile(path: IOPath): Boolean = toJavaFile(path).isFile

  override def isDirectory(path: IOPath): Boolean = toJavaFile(path).isDirectory

  // Returns true if the path is a symbolic link (without following it).
  private def isSymlinkPath(path: IOPath): Boolean = Zone {
    val buf = stackalloc[Byte](1)
    unistd.readlink(toCString(path.path), buf, 1.toUSize) >= 0
  }

  override def info(path: IOPath): FileInfo =
    val file = toJavaFile(path)
    if isSymlinkPath(path) then
      FileInfo(
        path = path,
        fileType = FileType.SymbolicLink,
        size = 0L,
        lastModified = None,
        isReadable = file.canRead,
        isWritable = file.canWrite,
        isExecutable = file.canExecute,
        isHidden = path.fileName.startsWith(".")
      )
    else if !file.exists() then
      FileInfo.notFound(path)
    else
      val fileType =
        if file.isFile then
          FileType.File
        else if file.isDirectory then
          FileType.Directory
        else
          FileType.Other

      FileInfo(
        path = path,
        fileType = fileType,
        size = file.length(),
        lastModified = Some(Instant.ofEpochMilli(file.lastModified())),
        isReadable = file.canRead,
        isWritable = file.canWrite,
        isExecutable = file.canExecute,
        isHidden = file.isHidden
      )
    end if

  end info

  override def readString(path: IOPath): String =
    new String(readBytes(path), StandardCharsets.UTF_8)

  override def readBytes(path: IOPath): Array[Byte] =
    val file = toJavaFile(path)
    val fis  = FileInputStream(file)
    try
      val buffer = ArrayBuffer[Byte]()
      val chunk  = new Array[Byte](8192)
      var read   = 0
      while {
        read = fis.read(chunk)
        read != -1
      } do
        buffer ++= chunk.take(read)
      buffer.toArray
    finally
      fis.close()

  override def readLines(path: IOPath): Seq[String] = readString(path).split("\n").toSeq

  override def readChunks(path: IOPath, chunkSize: Int): Iterator[Array[Byte]] =
    val in = FileInputStream(toJavaFile(path))
    CloseableChunkIterator(in, chunkSize)

  override def readStream(path: IOPath): InputStream = FileInputStream(toJavaFile(path))

  override def writeStream(path: IOPath, mode: WriteMode): OutputStream =
    val file   = toJavaFile(path)
    val parent = file.getParentFile
    if parent != null && !parent.exists() then
      parent.mkdirs()
    mode match
      case WriteMode.CreateNew =>
        if !file.createNewFile() then
          throw FileAlreadyExistsException(path.path)
        FileOutputStream(file, false)
      case WriteMode.Create =>
        FileOutputStream(file, false)
      case WriteMode.Append =>
        FileOutputStream(file, true)

  override def writeString(path: IOPath, content: String, mode: WriteMode): Unit = writeBytes(
    path,
    content.getBytes(StandardCharsets.UTF_8),
    mode
  )

  override def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode): Unit =
    val file = toJavaFile(path)

    // Create parent directories if needed
    val parent = file.getParentFile
    if parent != null && !parent.exists() then
      parent.mkdirs()

    mode match
      case WriteMode.CreateNew =>
        // Use createNewFile() for atomic check-and-create
        if !file.createNewFile() then
          throw FileAlreadyExistsException(path.path)
        val fos = FileOutputStream(file, false)
        try fos.write(content)
        finally fos.close()

      case WriteMode.Create =>
        val fos = FileOutputStream(file, false)
        try fos.write(content)
        finally fos.close()

      case WriteMode.Append =>
        val fos = FileOutputStream(file, true)
        try fos.write(content)
        finally fos.close()

  override def list(path: IOPath, options: ListOptions): Seq[IOPath] =
    val dir = toJavaFile(path)
    if !dir.isDirectory then
      Seq.empty
    else
      val maxDepth =
        if options.recursive then
          options.maxDepth.getOrElse(Int.MaxValue)
        else
          1

      def listRecursive(currentDir: File, currentDepth: Int): Seq[IOPath] =
        if currentDepth > maxDepth then
          Seq.empty
        else
          val files  = Option(currentDir.listFiles()).getOrElse(Array.empty[File])
          var result = files.toSeq.map(f => IOPath.parse(f.getPath))

          // Filter hidden files
          if !options.includeHidden then
            result = result.filterNot(p => toJavaFile(p).isHidden || p.fileName.startsWith("."))

          // Recurse into subdirectories
          if options.recursive && currentDepth < maxDepth then
            val subdirs = files.filter(_.isDirectory)
            result = result ++ subdirs.flatMap(subdir => listRecursive(subdir, currentDepth + 1))

          result

      var result = listRecursive(dir, 1)

      // Filter by extension (exclude directories when filtering by extension)
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

    end if

  end list

  override def createDirectory(path: IOPath): Unit =
    val file = toJavaFile(path)
    if !file.exists() then
      file.mkdirs()

  override def delete(path: IOPath): Boolean =
    val file = toJavaFile(path)
    if file.exists() then
      file.delete()
    else
      false

  override def deleteRecursively(path: IOPath): Boolean =
    val file = toJavaFile(path)
    if !file.exists() then
      false
    else
      deleteRecursivelyInternal(file)

  private def deleteRecursivelyInternal(file: File): Boolean =
    if file.isDirectory then
      Option(file.listFiles()).foreach(_.foreach(deleteRecursivelyInternal))
    file.delete()

  override def copy(source: IOPath, target: IOPath, options: CopyOptions): Unit =
    val sourceFile = toJavaFile(source)
    val targetFile = toJavaFile(target)

    if !sourceFile.exists() then
      throw java.io.FileNotFoundException(s"Source not found: ${source}")

    // Create parent directories
    val targetParent = targetFile.getParentFile
    if targetParent != null && !targetParent.exists() then
      targetParent.mkdirs()

    if sourceFile.isDirectory && options.recursive then
      copyDirectoryRecursive(sourceFile, targetFile, options)
    else
      if !options.overwrite && targetFile.exists() then
        throw FileAlreadyExistsException(target.path)
      copyFile(sourceFile, targetFile)

  private def copyFile(source: File, target: File): Unit =
    val fis = FileInputStream(source)
    try
      val fos = FileOutputStream(target)
      try
        val buffer = new Array[Byte](8192)
        var read   = 0
        while {
          read = fis.read(buffer)
          read != -1
        } do
          fos.write(buffer, 0, read)
      finally
        fos.close()
    finally
      fis.close()

  private def copyDirectoryRecursive(source: File, target: File, options: CopyOptions): Unit =
    if !target.exists() then
      target.mkdirs()

    Option(source.listFiles()).foreach { files =>
      files.foreach { file =>
        val targetChild = File(target, file.getName)
        if file.isDirectory then
          copyDirectoryRecursive(file, targetChild, options)
        else
          if !options.overwrite && targetChild.exists() then
            throw FileAlreadyExistsException(targetChild.getPath)
          copyFile(file, targetChild)
      }
    }

  override def move(source: IOPath, target: IOPath, overwrite: Boolean): Unit =
    val sourceFile = toJavaFile(source)
    val targetFile = toJavaFile(target)

    if !overwrite && targetFile.exists() then
      throw FileAlreadyExistsException(target.path)

    // Create parent directories
    val targetParent = targetFile.getParentFile
    if targetParent != null && !targetParent.exists() then
      targetParent.mkdirs()

    // Try rename first (atomic on same filesystem)
    if !sourceFile.renameTo(targetFile) then
      // Fallback to copy + delete
      copy(source, target, CopyOptions(overwrite = overwrite, recursive = true))
      deleteRecursively(source)

  override def createTempFile(prefix: String, suffix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.map(toJavaFile).getOrElse(File(System.getProperty("java.io.tmpdir")))
    if !dir.exists() then
      dir.mkdirs()
    val tempFile = File.createTempFile(prefix, suffix, dir)
    IOPath.parse(tempFile.getPath)

  override def createTempDirectory(prefix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.map(toJavaFile).getOrElse(File(System.getProperty("java.io.tmpdir")))
    if !dir.exists() then
      dir.mkdirs()
    // Create temp file and convert to directory
    val tempDir = File.createTempFile(prefix, "", dir)
    tempDir.delete()
    tempDir.mkdir()
    IOPath.parse(tempDir.getPath)

  // Async operations (wrap sync operations in Future)
  override def readStringAsync(path: IOPath): Future[String] = Future(readString(path))

  override def readBytesAsync(path: IOPath): Future[Array[Byte]] = Future(readBytes(path))

  override def writeStringAsync(path: IOPath, content: String, mode: WriteMode): Future[Unit] =
    Future(writeString(path, content, mode))

  override def writeBytesAsync(path: IOPath, content: Array[Byte], mode: WriteMode): Future[Unit] =
    Future(writeBytes(path, content, mode))

  override def listAsync(path: IOPath, options: ListOptions): Future[Seq[IOPath]] = Future(
    list(path, options)
  )

  override def infoAsync(path: IOPath): Future[FileInfo] = Future(info(path))

  override def existsAsync(path: IOPath): Future[Boolean] = Future(exists(path))

  override def createSymlink(link: IOPath, target: IOPath): Unit = Zone {
    val ret = unistd.symlink(toCString(target.path), toCString(link.path))
    if ret != 0 then
      throw java.io.IOException(s"Failed to create symlink: ${link.path} -> ${target.path}")
  }

  override def readSymlink(link: IOPath): IOPath = Zone {
    val buf = stackalloc[Byte](4096)
    val len = unistd.readlink(toCString(link.path), buf, 4095.toUSize)
    if len < 0 then
      throw java.io.IOException(s"Failed to read symlink: ${link.path}")
    buf(len.toInt) = 0.toByte
    IOPath.parse(fromCString(buf))
  }

end FileSystemNative

/**
  * Scala Native FileSystem initialization.
  */
object FileSystemInit:
  // Register Native implementation
  FileSystem.setImplementation(FileSystemNative)

  /**
    * Ensures the FileSystem is initialized.
    */
  def init(): Unit = ()

end FileSystemInit
