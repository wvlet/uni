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
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.Gzip
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest

class FileLogHandlerTest extends UniTest:
  FileSystemInit.init()
  private lazy val tempDir = FileSystem.createTempDirectory("fileloghandler-test")

  test("write logs to file") {
    val logPath = tempDir / "test1.log"
    val config  = FileLogHandlerConfig(logPath).noCompression.noRotation
    val handler = FileLogHandler(config)

    val logger = Logger("test1")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)

    logger.info("Hello, World!")
    logger.info("Second line")

    handler.close()

    val content = FileSystem.readString(logPath)
    content shouldContain "Hello, World!"
    content shouldContain "Second line"
  }

  test("rotate by size") {
    val logPath = tempDir / "test2.log"
    val config  =
      FileLogHandlerConfig(logPath)
        .withMaxSizeInBytes(100) // Very small size to trigger rotation
        .noCompression
    val handler = FileLogHandler(config)

    val logger = Logger("test2")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)

    // Write enough data to trigger rotation
    for i <- 1 to 10 do
      logger.info(s"Log message number ${i} with some padding to make it longer")

    handler.close()

    // Check that rotated files exist
    val files = FileSystem.list(tempDir).filter(_.fileName.startsWith("test2-"))
    (files.size >= 1) shouldBe true
  }

  test("compress rotated files") {
    val logPath = tempDir / "test3.log"
    val config  = FileLogHandlerConfig(logPath).withMaxSizeInBytes(100).withCompressRotated(true)
    val handler = FileLogHandler(config)

    val logger = Logger("test3")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)

    // Write enough data to trigger rotation
    for i <- 1 to 10 do
      logger.info(s"Log message number ${i} with some padding to make it longer")

    handler.close()

    // Check that compressed rotated files exist
    val gzFiles = FileSystem.list(tempDir).filter(_.fileName.endsWith(".log.gz"))
    (gzFiles.size >= 1) shouldBe true

    // Verify we can decompress one of them
    val gzFile       = gzFiles.head
    val decompressed = Gzip.decompress(FileSystem.readBytes(gzFile))
    val content      = String(decompressed, "UTF-8")
    content shouldContain "Log message number"
  }

  test("cleanup old files") {
    val logPath = tempDir / "test4.log"
    val config  =
      FileLogHandlerConfig(logPath)
        .withMaxSizeInBytes(50)
        .withMaxNumberOfFiles(2) // Keep only 2 rotated files
        .noCompression
    val handler = FileLogHandler(config)

    val logger = Logger("test4")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)

    // Write enough data to trigger multiple rotations
    for i <- 1 to 50 do
      logger.info(s"Log message number ${i}")

    handler.close()

    // Check that only maxNumberOfFiles rotated files exist
    val rotatedFiles = FileSystem
      .list(tempDir)
      .filter { p =>
        p.fileName.startsWith("test4-") && p.fileName.endsWith(".log")
      }
    (rotatedFiles.size <= 2) shouldBe true
  }

  test("FileLogHandlerConfig builder methods") {
    val config = FileLogHandlerConfig("app.log")
      .withMaxSizeInBytes(1000)
      .withMaxNumberOfFiles(10)
      .withLogFileExt(".txt")
      .withCompressRotated(false)

    config.maxSizeInBytes shouldBe 1000
    config.maxNumberOfFiles shouldBe 10
    config.logFileExt shouldBe ".txt"
    config.compressRotated shouldBe false
  }

  test("noRotation disables rotation") {
    val config = FileLogHandlerConfig("app.log").noRotation
    config.maxSizeInBytes shouldBe Long.MaxValue
    config.maxNumberOfFiles shouldBe Int.MaxValue
  }

  test("rotate by date change") {
    val logPath = tempDir / "test5.log"

    // Start with a specific date (2026-01-15 12:00:00 UTC)
    val day1Millis = 1768478400000L                    // 2026-01-15 00:00:00 UTC
    val day2Millis = day1Millis + 24L * 60 * 60 * 1000 // Next day

    var currentTime = day1Millis
    val mockClock   = () => currentTime

    val config  = FileLogHandlerConfig(logPath).noCompression.withClock(mockClock)
    val handler = FileLogHandler(config)

    val logger = Logger("test5")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)

    // Write logs on day 1
    logger.info("Day 1 message 1")
    logger.info("Day 1 message 2")

    // Advance to day 2
    currentTime = day2Millis

    // Write logs on day 2 - should trigger rotation
    logger.info("Day 2 message 1")

    handler.close()

    // Check that a rotated file exists with day 1 date
    val rotatedFiles = FileSystem
      .list(tempDir)
      .filter { p =>
        p.fileName.startsWith("test5-") && p.fileName.contains("2026-01-15")
      }
    (rotatedFiles.size >= 1) shouldBe true

    // Verify the rotated file contains day 1 messages
    val rotatedContent = FileSystem.readString(rotatedFiles.head)
    rotatedContent shouldContain "Day 1 message"

    // Verify current log contains day 2 message
    val currentContent = FileSystem.readString(logPath)
    currentContent shouldContain "Day 2 message"
  }

  test("recover .rotating temp file when active log is missing") {
    val logPath  = tempDir / "test6.log"
    val tempPath = tempDir / ".test6.log.rotating"

    // Simulate a crash mid-rotation: the active log was moved to the temp path and the
    // process died before compression could finish.
    FileSystem.writeString(tempPath, "log line that survived the crash\n")
    FileSystem.exists(logPath) shouldBe false

    // Touching FileLogHandler must surface the orphaned content as the active log.
    val handler = FileLogHandler(FileLogHandlerConfig(logPath).noCompression)
    val logger  = Logger("test6")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)
    logger.info("first message after restart")

    handler.close()

    FileSystem.exists(tempPath) shouldBe false
    val content = FileSystem.readString(logPath)
    content shouldContain "log line that survived the crash"
    content shouldContain "first message after restart"
  }

  test("archive .rotating temp file when active log already exists") {
    val logPath  = tempDir / "test7.log"
    val tempPath = tempDir / ".test7.log.rotating"

    // Active log already has fresh content (e.g. process restarted then started logging
    // before recovery ran in a different handler).
    FileSystem.writeString(logPath, "post-restart content\n")
    FileSystem.writeString(tempPath, "pre-crash content\n")

    val handler = FileLogHandler(FileLogHandlerConfig(logPath).noCompression)
    val logger  = Logger("test7")
    logger.resetHandler(handler)
    logger.setLogLevel(LogLevel.INFO)
    logger.info("logged after recovery")
    handler.close()

    FileSystem.exists(tempPath) shouldBe false
    FileSystem.readString(logPath) shouldContain "post-restart content"

    // The orphan must be archived under a recovered-* name, not silently dropped.
    val recovered = FileSystem
      .list(tempDir)
      .filter(p => p.fileName.startsWith("test7-recovered-") && p.fileName.endsWith(".log"))
    recovered.size shouldBe 1
    FileSystem.readString(recovered.head) shouldContain "pre-crash content"
  }

end FileLogHandlerTest
