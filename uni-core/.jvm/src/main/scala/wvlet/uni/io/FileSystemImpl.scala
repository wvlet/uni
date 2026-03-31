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

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Comparator
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
  * JVM implementation of FileSystem using Java NIO.
  */
private[io] object FileSystemJvm extends FileSystemBase:
  // Initialize platform settings
  IOPath.separator = File.separator
  IOPath.isWindows = System.getProperty("os.name", "").toLowerCase.contains("win")

  private given ExecutionContext = ExecutionContext.global

  private def toNioPath(path: IOPath): Path = Paths.get(path.path)

  private def fromNioPath(path: Path): IOPath = IOPath.parse(path.toString)

  override def currentDirectory: IOPath = IOPath.parse(System.getProperty("user.dir", "."))

  override def homeDirectory: IOPath = IOPath.parse(System.getProperty("user.home", "."))

  override def tempDirectory: IOPath = IOPath.parse(System.getProperty("java.io.tmpdir", "/tmp"))

  override def isBrowser: Boolean = false
  override def isNode: Boolean    = false

  override def exists(path: IOPath): Boolean = Files.exists(toNioPath(path))

  override def isFile(path: IOPath): Boolean = Files.isRegularFile(toNioPath(path))

  override def isDirectory(path: IOPath): Boolean = Files.isDirectory(toNioPath(path))

  override def info(path: IOPath): FileInfo =
    val nioPath = toNioPath(path)
    try
      if !Files.exists(nioPath) then
        FileInfo.notFound(path)
      else
        val attrs    = Files.readAttributes(nioPath, classOf[BasicFileAttributes])
        val fileType =
          if attrs.isRegularFile then
            FileType.File
          else if attrs.isDirectory then
            FileType.Directory
          else if attrs.isSymbolicLink then
            FileType.SymbolicLink
          else
            FileType.Other

        FileInfo(
          path = path,
          fileType = fileType,
          size = attrs.size(),
          lastModified = Some(attrs.lastModifiedTime().toInstant),
          lastAccessed = Some(attrs.lastAccessTime().toInstant),
          createdAt = Some(attrs.creationTime().toInstant),
          isReadable = Files.isReadable(nioPath),
          isWritable = Files.isWritable(nioPath),
          isExecutable = Files.isExecutable(nioPath),
          isHidden = Files.isHidden(nioPath)
        )
    catch
      case _: Throwable =>
        // Handle race condition where file is deleted between exists check and attribute read
        FileInfo.notFound(path)

    end try

  end info

  override def readString(path: IOPath): String = Files.readString(
    toNioPath(path),
    StandardCharsets.UTF_8
  )

  override def readBytes(path: IOPath): Array[Byte] = Files.readAllBytes(toNioPath(path))

  override def readLines(path: IOPath): Seq[String] =
    Files.readAllLines(toNioPath(path), StandardCharsets.UTF_8).asScala.toSeq

  override def readLinesLazy(path: IOPath): Iterator[String] =
    val reader = Files.newBufferedReader(toNioPath(path), StandardCharsets.UTF_8)
    CloseableLineIterator(reader)

  override def readChunks(path: IOPath, chunkSize: Int): Iterator[Array[Byte]] =
    val in = Files.newInputStream(toNioPath(path))
    CloseableChunkIterator(in, chunkSize)

  override def readStream(path: IOPath): InputStream = Files.newInputStream(toNioPath(path))

  override def writeStream(path: IOPath, mode: WriteMode): OutputStream =
    val nioPath = toNioPath(path)
    val parent  = nioPath.getParent
    if parent != null && !Files.exists(parent) then
      Files.createDirectories(parent)
    val options =
      mode match
        case WriteMode.CreateNew =>
          Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        case WriteMode.Create =>
          Array(
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        case WriteMode.Append =>
          Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
    Files.newOutputStream(nioPath, options*)

  private def withFileAlreadyExistsHandler[A](body: => A): A =
    try
      body
    catch
      case e: java.nio.file.FileAlreadyExistsException =>
        throw FileAlreadyExistsException(e.getMessage)

  override def writeString(path: IOPath, content: String, mode: WriteMode): Unit =
    val nioPath = toNioPath(path)
    // Create parent directories if needed
    val parent = nioPath.getParent
    if parent != null && !Files.exists(parent) then
      Files.createDirectories(parent)

    val options =
      mode match
        case WriteMode.CreateNew =>
          Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        case WriteMode.Create =>
          Array(
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        case WriteMode.Append =>
          Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)

    withFileAlreadyExistsHandler {
      Files.writeString(nioPath, content, StandardCharsets.UTF_8, options*)
    }

  override def writeBytes(path: IOPath, content: Array[Byte], mode: WriteMode): Unit =
    val nioPath = toNioPath(path)
    // Create parent directories if needed
    val parent = nioPath.getParent
    if parent != null && !Files.exists(parent) then
      Files.createDirectories(parent)

    val options =
      mode match
        case WriteMode.CreateNew =>
          Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        case WriteMode.Create =>
          Array(
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
          )
        case WriteMode.Append =>
          Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)

    withFileAlreadyExistsHandler {
      Files.write(nioPath, content, options*)
    }

  override def list(path: IOPath, options: ListOptions): Seq[IOPath] =
    val nioPath = toNioPath(path)
    if !Files.isDirectory(nioPath) then
      Seq.empty
    else
      val maxDepth =
        if options.recursive then
          options.maxDepth.getOrElse(Int.MaxValue)
        else
          1

      val stream = Files.walk(nioPath, maxDepth)
      try
        var result = stream
          .iterator()
          .asScala
          .toSeq
          .filter(_ != nioPath) // Exclude the directory itself
          .map(fromNioPath)

        // Filter hidden files
        if !options.includeHidden then
          result = result.filterNot { p =>
            try
              Files.isHidden(toNioPath(p))
            catch
              case _: Exception =>
                false
          }

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
      finally
        stream.close()

      end try

    end if

  end list

  override def createDirectory(path: IOPath): Unit = Files.createDirectories(toNioPath(path))

  override def delete(path: IOPath): Boolean = Files.deleteIfExists(toNioPath(path))

  override def deleteRecursively(path: IOPath): Boolean =
    val nioPath = toNioPath(path)
    if !Files.exists(nioPath) then
      false
    else
      if Files.isDirectory(nioPath) then
        Files.walkFileTree(
          nioPath,
          new SimpleFileVisitor[Path]:
            override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
              Files.delete(file)
              FileVisitResult.CONTINUE
            override def postVisitDirectory(dir: Path, exc: java.io.IOException): FileVisitResult =
              if exc != null then
                throw exc
              Files.delete(dir)
              FileVisitResult.CONTINUE
        )
      else
        Files.delete(nioPath)
      true

  override def copy(source: IOPath, target: IOPath, options: CopyOptions): Unit =
    val sourcePath = toNioPath(source)
    val targetPath = toNioPath(target)

    val copyOptions = scala.collection.mutable.ArrayBuffer.empty[java.nio.file.CopyOption]
    if options.overwrite then
      copyOptions += StandardCopyOption.REPLACE_EXISTING
    if options.preserveAttributes then
      copyOptions += StandardCopyOption.COPY_ATTRIBUTES

    if Files.isDirectory(sourcePath) && options.recursive then
      // Recursive directory copy
      Files.walkFileTree(
        sourcePath,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            val targetDir = targetPath.resolve(sourcePath.relativize(dir))
            if !Files.exists(targetDir) then
              Files.createDirectories(targetDir)
            FileVisitResult.CONTINUE
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            val targetFile = targetPath.resolve(sourcePath.relativize(file))
            Files.copy(file, targetFile, copyOptions.toSeq*)
            FileVisitResult.CONTINUE
      )
    else
      // Create parent directories if needed
      val parent = targetPath.getParent
      if parent != null && !Files.exists(parent) then
        Files.createDirectories(parent)
      Files.copy(sourcePath, targetPath, copyOptions.toSeq*)

  end copy

  override def move(source: IOPath, target: IOPath, overwrite: Boolean): Unit =
    val sourcePath = toNioPath(source)
    val targetPath = toNioPath(target)
    val options    =
      if overwrite then
        Array(StandardCopyOption.REPLACE_EXISTING)
      else
        Array.empty[StandardCopyOption]

    // Create parent directories if needed
    val parent = targetPath.getParent
    if parent != null && !Files.exists(parent) then
      Files.createDirectories(parent)

    try
      Files.move(sourcePath, targetPath, options*)
    catch
      case _: java.nio.file.FileSystemException =>
        // Fallback to copy-and-delete for cross-filesystem moves
        copy(source, target, CopyOptions(overwrite = overwrite, recursive = true))
        deleteRecursively(source)

  override def createTempFile(prefix: String, suffix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.map(toNioPath).getOrElse(Paths.get(System.getProperty("java.io.tmpdir")))
    fromNioPath(Files.createTempFile(dir, prefix, suffix))

  override def createTempDirectory(prefix: String, directory: Option[IOPath]): IOPath =
    val dir = directory.map(toNioPath).getOrElse(Paths.get(System.getProperty("java.io.tmpdir")))
    fromNioPath(Files.createTempDirectory(dir, prefix))

  // Async operations (wrap sync operations in Future for JVM)
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

end FileSystemJvm

private[io] class CloseableLineIterator(reader: BufferedReader)
    extends Iterator[String]
    with AutoCloseable:
  private var nextLine: String | Null = reader.readLine()

  override def hasNext: Boolean =
    val has = nextLine != null
    if !has then
      close()
    has

  override def next(): String =
    val line = nextLine
    if line == null then
      throw java.util.NoSuchElementException("No more lines")
    nextLine = reader.readLine()
    line

  override def close(): Unit = reader.close()

end CloseableLineIterator

private[io] class CloseableChunkIterator(in: InputStream, chunkSize: Int)
    extends Iterator[Array[Byte]]
    with AutoCloseable:
  private val buffer                        = new Array[Byte](chunkSize)
  private var bytesRead: Int                = in.read(buffer)
  private var nextChunk: Array[Byte] | Null =
    if bytesRead == -1 then
      null
    else if bytesRead == chunkSize then
      buffer.clone()
    else
      java.util.Arrays.copyOf(buffer, bytesRead)

  override def hasNext: Boolean =
    val has = nextChunk != null
    if !has then
      close()
    has

  override def next(): Array[Byte] =
    val chunk = nextChunk
    if chunk == null then
      throw java.util.NoSuchElementException("No more chunks")
    // Read the next chunk
    bytesRead = in.read(buffer)
    nextChunk =
      if bytesRead == -1 then
        null
      else if bytesRead == chunkSize then
        buffer.clone()
      else
        java.util.Arrays.copyOf(buffer, bytesRead)
    chunk

  override def close(): Unit = in.close()

end CloseableChunkIterator

/**
  * JVM FileSystem initialization. This object ensures the JVM implementation is registered.
  */
object FileSystemInit:
  // Register JVM implementation
  FileSystem.setImplementation(FileSystemJvm)

  /**
    * Ensures the FileSystem is initialized. Call this at application startup if needed.
    */
  def init(): Unit = ()

end FileSystemInit
