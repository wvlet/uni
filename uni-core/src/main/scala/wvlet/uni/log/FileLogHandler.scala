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
package wvlet.uni.log

import wvlet.uni.io.FileSystem
import wvlet.uni.io.Gzip
import wvlet.uni.io.IOPath
import wvlet.uni.io.WriteMode
import wvlet.uni.log.LogFormatter.AppLogFormatter

import java.io.Flushable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.logging as jl
import java.util.logging.ErrorManager
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
  * Configuration for FileLogHandler.
  *
  * @param path
  *   The log file path
  * @param maxSizeInBytes
  *   Maximum size of a log file before rotation (default: 100 MB)
  * @param maxNumberOfFiles
  *   Maximum number of rotated files to keep (default: 100)
  * @param formatter
  *   Log formatter (default: AppLogFormatter)
  * @param logFileExt
  *   Log file extension (default: ".log")
  * @param compressRotated
  *   Whether to compress rotated files with gzip (default: true)
  * @param clock
  *   Clock function returning current time in milliseconds (default: System.currentTimeMillis). Can
  *   be overridden for testing.
  */
case class FileLogHandlerConfig(
    path: IOPath,
    maxSizeInBytes: Long = 104857600L,
    maxNumberOfFiles: Int = 100,
    formatter: LogFormatter = AppLogFormatter,
    logFileExt: String = ".log",
    compressRotated: Boolean = true,
    clock: () => Long = () => System.currentTimeMillis()
):
  def withPath(p: IOPath): FileLogHandlerConfig                    = copy(path = p)
  def withMaxSizeInBytes(size: Long): FileLogHandlerConfig         = copy(maxSizeInBytes = size)
  def withMaxNumberOfFiles(n: Int): FileLogHandlerConfig           = copy(maxNumberOfFiles = n)
  def withFormatter(f: LogFormatter): FileLogHandlerConfig         = copy(formatter = f)
  def withLogFileExt(ext: String): FileLogHandlerConfig            = copy(logFileExt = ext)
  def withCompressRotated(compress: Boolean): FileLogHandlerConfig = copy(compressRotated =
    compress
  )

  def withClock(c: () => Long): FileLogHandlerConfig = copy(clock = c)

  def noCompression: FileLogHandlerConfig = copy(compressRotated = false)
  def noRotation: FileLogHandlerConfig    = copy(
    maxSizeInBytes = Long.MaxValue,
    maxNumberOfFiles = Int.MaxValue
  )

end FileLogHandlerConfig

object FileLogHandlerConfig:
  def apply(path: String): FileLogHandlerConfig = FileLogHandlerConfig(IOPath(path))

/**
  * Cross-platform file log handler with time-based (daily) and size-based rotation.
  *
  * This handler uses the cross-platform FileSystem API and works on JVM, Scala.js (Node.js), and
  * Scala Native.
  *
  * Features:
  *   - Daily rotation: rotates at midnight
  *   - Size-based rotation: rotates when file exceeds maxSizeInBytes
  *   - Gzip compression of rotated files (on supported platforms)
  *   - Automatic cleanup of old rotated files
  *
  * File naming convention:
  *   - Active log: {path}
  *   - Rotated: {stem}-YYYY-MM-DD.{index}.log.gz
  */
class FileLogHandler(config: FileLogHandlerConfig)
    extends jl.Handler
    with AutoCloseable
    with Flushable:

  setFormatter(config.formatter)

  private val logPath      = config.path
  private val logDir       = logPath.parent.getOrElse(IOPath("."))
  private val fileNameStem =
    val name = logPath.fileName
    if name.endsWith(config.logFileExt) then
      name.substring(0, name.length - config.logFileExt.length)
    else
      name

  // Cross-platform date formatting (yyyy-MM-dd)
  private def formatDate(date: LocalDate): String =
    f"${date.getYear}%04d-${date.getMonthValue}%02d-${date.getDayOfMonth}%02d"

  // Cross-platform conversion from Instant to LocalDate (UTC-based for consistency)
  private def instantToLocalDate(instant: Instant): LocalDate =
    val epochDay = instant.toEpochMilli / (24L * 60 * 60 * 1000)
    LocalDate.ofEpochDay(epochDay)

  // Get today's date in UTC for consistent cross-platform rotation
  private def todayUtc(): LocalDate =
    val nowMs    = config.clock()
    val epochDay = nowMs / (24L * 60 * 60 * 1000)
    LocalDate.ofEpochDay(epochDay)

  @volatile
  private var currentFileSize = 0L

  @volatile
  private var currentDate: LocalDate = null

  @volatile
  private var initialized = false

  // Lock for thread-safe file operations
  private val lock = new Object

  private def rotatingTempPath: IOPath = logDir.resolve(s".${logPath.fileName}.rotating")

  // Recover a temp file left behind by a previous run that crashed mid-rotation.
  // The .rotating temp holds the pre-compression log content; if logPath is absent we restore
  // it as the active log, otherwise we archive it under a unique recovery name so log data
  // isn't silently lost (cleanupOldFiles ignores the suffix).
  private def recoverRotatingFile(): Unit =
    val tempPath = rotatingTempPath
    if FileSystem.exists(tempPath) then
      val target =
        if !FileSystem.exists(logPath) then
          logPath
        else
          logDir.resolve(s"${fileNameStem}-recovered-${config.clock()}${config.logFileExt}")
      Try(FileSystem.move(tempPath, target)) match
        case Failure(e) =>
          reportError(
            s"Failed to recover rotating temp file ${tempPath} to ${target}",
            toException(e),
            ErrorManager.OPEN_FAILURE
          )
        case Success(_) =>
        // recovered

  private def init(): Unit = lock.synchronized:
    if !initialized then
      // Create directory if it doesn't exist
      FileSystem.createDirectoryIfNotExists(logDir)
      // Recover any leftover .rotating temp file before reading logPath's size/date
      recoverRotatingFile()

      if FileSystem.exists(logPath) then
        val info = FileSystem.info(logPath)
        currentFileSize = info.size
        // Use file's last modified date to ensure proper rotation after restart
        currentDate = info.lastModified.map(instantToLocalDate).getOrElse(todayUtc())
      else
        currentFileSize = 0L
        currentDate = todayUtc()

      initialized = true

  override def flush(): Unit = lock.synchronized:
    // FileSystem.appendString handles flushing internally
    ()

  private def toException(t: Throwable) = Exception(t.getMessage, t)

  override def publish(record: jl.LogRecord): Unit =
    if isLoggable(record) then
      Try(config.formatter.format(record)) match
        case Success(message) =>
          Try(writeMessage(s"${message}\n")) match
            case Success(_) =>
            // do nothing
            case Failure(e) =>
              reportError(null, toException(e), ErrorManager.WRITE_FAILURE)
        case Failure(e) =>
          reportError(null, toException(e), ErrorManager.FORMAT_FAILURE)

  private def writeMessage(message: String): Unit = lock.synchronized:
    if !initialized then
      init()
    checkRotation()
    FileSystem.appendString(logPath, message)
    currentFileSize += message.getBytes(StandardCharsets.UTF_8).length

  // Check if rotation is disabled (both size and file count at max)
  private def isRotationDisabled: Boolean =
    config.maxSizeInBytes == Long.MaxValue && config.maxNumberOfFiles == Int.MaxValue

  private def checkRotation(): Unit =
    // Skip rotation check entirely if rotation is disabled
    if isRotationDisabled then
      return

    val today         = todayUtc()
    val needsRotation =
      initialized && (
        (currentDate != null && !currentDate.equals(today)) ||
          (config.maxSizeInBytes != Long.MaxValue && currentFileSize >= config.maxSizeInBytes)
      )

    if needsRotation then
      val rotationSucceeded = rotate()
      if rotationSucceeded then
        currentDate = today
        currentFileSize = 0L

  /** Returns true if rotation was successful */
  private def rotate(): Boolean =
    if FileSystem.exists(logPath) && FileSystem.info(logPath).size > 0 then
      // Find the next available index for the current date
      val dateStr =
        if currentDate != null then
          formatDate(currentDate)
        else
          formatDate(todayUtc())
      val index = findNextIndex(dateStr)

      val rotatedExt =
        if config.compressRotated then
          s"${config.logFileExt}.gz"
        else
          config.logFileExt

      val rotatedFileName = s"${fileNameStem}-${dateStr}.${index}${rotatedExt}"
      val rotatedPath     = logDir.resolve(rotatedFileName)

      val rotationResult = Try {
        if config.compressRotated then
          // Move first for atomicity, then compress
          // This ensures we don't lose data if compression fails
          val tempPath = rotatingTempPath
          FileSystem.move(logPath, tempPath)
          try
            Gzip.compressFile(tempPath, rotatedPath)
            FileSystem.delete(tempPath)
          catch
            case e: Throwable =>
              // Compression failed - restore original file
              FileSystem.move(tempPath, logPath)
              throw e
        else
          // Just move without compression
          FileSystem.move(logPath, rotatedPath)
      }

      rotationResult match
        case Failure(e) =>
          reportError(
            s"Failed to rotate ${logPath} to ${rotatedPath}",
            toException(e),
            ErrorManager.GENERIC_FAILURE
          )
          false
        case Success(_) =>
          // Clean up old files only on successful rotation
          cleanupOldFiles()
          true
    else
      // No file to rotate or file is empty
      true

  end rotate

  private def findNextIndex(dateStr: String): Int =
    val prefix = s"${fileNameStem}-${dateStr}."
    val suffix =
      if config.compressRotated then
        s"${config.logFileExt}.gz"
      else
        config.logFileExt

    val existingIndices = FileSystem
      .list(logDir)
      .flatMap { p =>
        val name = p.fileName
        if name.startsWith(prefix) && name.endsWith(suffix) then
          val middle = name.substring(prefix.length, name.length - suffix.length)
          Try(middle.toInt).toOption
        else
          None
      }

    if existingIndices.isEmpty then
      0
    else
      existingIndices.max + 1

  private def cleanupOldFiles(): Unit =
    if config.maxNumberOfFiles < Int.MaxValue then
      val prefix = s"${fileNameStem}-"
      val suffix =
        if config.compressRotated then
          s"${config.logFileExt}.gz"
        else
          config.logFileExt

      val rotatedFiles = FileSystem
        .list(logDir)
        .filter { p =>
          val name = p.fileName
          name.startsWith(prefix) && name.endsWith(suffix)
        }
        .sortBy { p =>
          FileSystem.info(p).lastModified.map(_.toEpochMilli).getOrElse(0L)
        }

      if rotatedFiles.length > config.maxNumberOfFiles then
        val filesToDelete = rotatedFiles.take(rotatedFiles.length - config.maxNumberOfFiles)
        filesToDelete.foreach { p =>
          Try(FileSystem.delete(p)) match
            case Failure(e) =>
              reportError(
                s"Failed to delete old log file ${p}",
                toException(e),
                ErrorManager.GENERIC_FAILURE
              )
            case Success(_) =>
            // Deletion successful
        }

  override def close(): Unit = lock.synchronized:
    // Nothing to close - FileSystem handles file closing internally
    ()

end FileLogHandler

object FileLogHandler:
  /**
    * Creates a FileLogHandler with the given path.
    */
  def apply(path: String): FileLogHandler = FileLogHandler(FileLogHandlerConfig(path))

  /**
    * Creates a FileLogHandler with the given path.
    */
  def apply(path: IOPath): FileLogHandler = FileLogHandler(FileLogHandlerConfig(path))

  /**
    * Creates a FileLogHandler with the given config.
    */
  def apply(config: FileLogHandlerConfig): FileLogHandler = new FileLogHandler(config)

end FileLogHandler
