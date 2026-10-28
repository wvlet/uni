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
package wvlet.uni.test

import wvlet.uni.log.{LogLevel, LogRecord, Logger}
import wvlet.uni.rx.RxOps
import wvlet.uni.test.spi.TestLogCapture

import java.io.ByteArrayOutputStream
import java.util.logging as jl
import scala.compiletime.summonInline
import scala.concurrent.Future
import scala.reflect.ClassTag

/**
  * Log records captured by [[OutputCapture.captureLogs]]
  */
case class CapturedLogs(records: Seq[LogRecord]):
  private def lines(rs: Seq[LogRecord]): String = rs
    .map { r =>
      r.cause match
        case Some(e) =>
          s"${r.message}\n${e}"
        case None =>
          r.message
    }
    .mkString("\n")

  /**
    * All captured messages, one per line. A record with an exception adds a line with the
    * exception.
    */
  def messages: String = lines(records)

  /**
    * Messages of WARN records, one per line
    */
  def warnings: String = lines(records.filter(_.level == LogLevel.WARN))

  /**
    * Messages of ERROR records, one per line
    */
  def errors: String = lines(records.filter(_.level == LogLevel.ERROR))

  def isEmpty: Boolean  = records.isEmpty
  def nonEmpty: Boolean = records.nonEmpty

end CapturedLogs

/**
  * Capture log records and console output of code that fails on purpose, so a test can assert on
  * them without printing them.
  */
trait OutputCapture:

  /**
    * Run `body` and return the log records emitted during it, instead of printing them.
    *
    * Records are taken from the current thread and from threads that run no suite, such as a
    * server's worker threads and Future pools. Records from threads running another suite are left
    * alone. If `body` throws, the captured records are printed, so the failure stays debuggable.
    * Log levels are not changed: a record below its logger's level is not emitted, so it is not
    * captured either. The block must be synchronous: a block that returns a Future or Rx fails.
    *
    * With a type argument, `captureLogs[A] { ... }` captures only records of the logger of `A` and
    * its children. Use it for long blocks, so that records of unrelated code stay visible.
    */
  inline def captureLogs[A](body: => Any): CapturedLogs =
    capture(loggerNameOf(summonInline[ClassTag[A]]))(body)

  // An omitted type argument is inferred as Any, which means every logger
  private[test] def loggerNameOf(tag: ClassTag[?]): Option[String] =
    val cls = tag.runtimeClass
    if cls == classOf[Object] || cls == classOf[scala.runtime.Nothing$] then
      None
    else
      Some(Logger.loggerNameOf(cls))

  private[test] def capture(loggerName: Option[String])(body: => Any): CapturedLogs =
    val c       = TestLogCapture.openCapture(loggerName)
    val records =
      try
        checkSynchronous(body)
        c.close(replay = false)
      catch
        case e: Throwable =>
          c.close(replay = true)
          throw e
    CapturedLogs(records.map(toLogRecord))

  // A Future or Rx would run after the capture ends, and the assertions in it would be dropped
  private def checkSynchronous(result: Any): Unit =
    result match
      case _: Future[?] | _: RxOps[?] =>
        throw IllegalArgumentException(
          "The block returned a Future or Rx, which runs after the capture ends. Capture inside the asynchronous code, or return its result after the capture."
        )
      case _ =>

  private def toLogRecord(r: jl.LogRecord): LogRecord =
    r match
      case l: LogRecord =>
        l
      case other =>
        LogRecord(other)

  /**
    * Run `body` and return what it printed to `Console.out` (`println`), instead of printing it.
    * Writes to `System.out` from Java code are not captured.
    */
  def captureStdout(body: => Any): String = captureConsole(Console.out)(Console.withOut(_)(body))

  /**
    * Run `body` and return what it printed to `Console.err`, instead of printing it. Writes to
    * `System.err` from Java code are not captured.
    */
  def captureStderr(body: => Any): String = captureConsole(Console.err)(Console.withErr(_)(body))

  private def captureConsole(original: java.io.PrintStream)(
      run: java.io.OutputStream => Any
  ): String =
    val buf = ByteArrayOutputStream()
    try
      run(buf)
      buf.toString
    catch
      case e: Throwable =>
        // Show what was printed before the failure
        original.print(buf.toString)
        throw e

end OutputCapture
