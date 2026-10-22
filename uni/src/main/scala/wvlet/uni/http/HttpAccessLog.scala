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
package wvlet.uni.http

import wvlet.uni.log.{FileLogHandler, FileLogHandlerConfig, LogFormatter, LogLevel, LogRecord}
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.codec.AnyWeaver

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
  * Configuration of [[HttpAccessLogFilter]].
  *
  * @param streamWriter
  *   Where the entries for finished Server-Sent Events streams go. Default: the filter's writer
  * @param redactedHeaders
  *   Header names (case-insensitive) whose values are logged as `[redacted]`
  * @param excludedHeaders
  *   Header names (case-insensitive) left out of the log
  * @param extraEntries
  *   Adds application fields to every entry, e.g. a user id from a request attribute. The response
  *   is `None` when the handler failed
  */
case class HttpAccessLogConfig(
    streamWriter: Option[HttpAccessLogWriter] = None,
    redactedHeaders: Set[String] = HttpAccessLogConfig.DefaultRedactedHeaders,
    excludedHeaders: Set[String] = Set.empty,
    extraEntries: (Request, Option[Response]) => Map[String, Any] = (_, _) => Map.empty
):
  def withStreamWriter(w: HttpAccessLogWriter): HttpAccessLogConfig = copy(streamWriter = Some(w))
  def noStreamWriter(): HttpAccessLogConfig                         = copy(streamWriter = None)

  def withRedactedHeaders(names: String*): HttpAccessLogConfig = copy(redactedHeaders =
    names.map(_.toLowerCase).toSet
  )

  def withExcludedHeaders(names: String*): HttpAccessLogConfig = copy(excludedHeaders =
    names.map(_.toLowerCase).toSet
  )

  def withExtraEntries(f: (Request, Option[Response]) => Map[String, Any]): HttpAccessLogConfig =
    copy(extraEntries = f)

object HttpAccessLogConfig:
  val DefaultRedactedHeaders: Set[String] = Set(
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key"
  )

/**
  * An [[RxHttpFilter]] that writes one entry per request, in the field names of Airframe's
  * `HttpLogger`: `time`, `event_timestamp`, `method`, `path`, `uri`, `query_string`,
  * `request_size`, `remote_address`, `host`, `user_agent`, `status_code`, `status_code_name`,
  * `response_time_ms`, `response_size`, `request_header`, `response_header`, and `exception_class`
  * / `exception_message` when the handler failed.
  *
  * Add it as the outermost filter so it sees the final response; inner filters and controllers add
  * fields to the entry with [[HttpAccessLogFilter.addEntry]]. A handler exception is logged with
  * status 500 and passed on, so the server still renders it. For a Server-Sent Events response, a
  * second entry is written to the stream writer when the stream ends, with `stream_event_count`,
  * `stream_bytes`, and `stream_duration_ms`.
  *
  * Requests the server rejects before filters run (oversized or malformed bodies, read timeouts,
  * the host check) are not logged. An SSE response is logged with its own status even if the server
  * then rejects the stream (503 over `maxStreamingConnections`).
  */
class HttpAccessLogFilter(
    writer: HttpAccessLogWriter,
    config: HttpAccessLogConfig = HttpAccessLogConfig()
) extends RxHttpFilter:

  def withConfig(c: HttpAccessLogConfig): HttpAccessLogFilter = HttpAccessLogFilter(writer, c)

  override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    val startMillis = System.currentTimeMillis()
    val startNanos  = System.nanoTime()
    val context     = HttpAccessLogFilter.LogContext()
    val rx          =
      try
        next.handle(request.withAttribute(HttpAccessLogFilter.ContextKey, context))
      catch
        case NonFatal(e) =>
          Rx.exception(e)
    // Log once: the server answers only the first response, and a failure raised after it is
    // re-fed as an error that must not be logged as a second request
    val logged    = AtomicBoolean(false)
    val responses = rx.transformTry {
      case Success(response) =>
        if logged.compareAndSet(false, true) then
          write(
            writer,
            requestEntry(request, startMillis) ++ responseEntry(response, startNanos) ++
              context.entries ++ extraEntries(request, Some(response))
          )
          if response.isEventStream then
            Success(response.withEvents(logStream(request, response, startMillis, context)))
          else
            Success(response)
        else
          Success(response)
      case Failure(e) =>
        if logged.compareAndSet(false, true) then
          val status = HttpStatus.InternalServerError_500
          write(
            writer,
            requestEntry(request, startMillis) ++ statusEntry(status, startNanos) ++
              exceptionEntry(e) ++ context.entries ++ extraEntries(request, None)
          )
        Failure(e)
    }
    // A handler Rx that completes without a response is answered with 404 by the server
    val onEmpty: Rx[Response] = Rx
      .single(())
      .flatMap { _ =>
        if logged.compareAndSet(false, true) then
          write(
            writer,
            requestEntry(request, startMillis) ++
              statusEntry(HttpStatus.NotFound_404, startNanos) ++ context.entries ++
              extraEntries(request, None)
          )
        Rx.empty[Response]
      }
    responses.concat(onEmpty)

  end apply

  private def logStream(
      request: Request,
      response: Response,
      startMillis: Long,
      context: HttpAccessLogFilter.LogContext
  ): Rx[ServerSentEvent] =
    val streamStartNanos                                 = System.nanoTime()
    var count                                            = 0L
    var bytes                                            = 0L
    def writeStreamEntry(error: Option[Throwable]): Unit =
      val entry =
        requestEntry(request, startMillis) ++
          Map(
            "status_code"        -> response.status.code,
            "stream_event_count" -> count,
            "stream_bytes"       -> bytes,
            "stream_duration_ms" -> elapsedMillis(streamStartNanos)
          ) ++ error.map(exceptionEntry).getOrElse(Map.empty) ++ context.entries ++
          extraEntries(request, Some(response))
      write(config.streamWriter.getOrElse(writer), entry)

    val counted = response
      .events
      .tap { e =>
        count += 1
        bytes += e.toContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      }
      .tapOnFailure(e => writeStreamEntry(Some(e)))
    // Runs after the last event, since concat subscribes to it only when `counted` completes
    val onCompletion: Rx[ServerSentEvent] = Rx
      .single(())
      .flatMap { _ =>
        writeStreamEntry(None)
        Rx.empty[ServerSentEvent]
      }
    counted.concat(onCompletion)

  end logStream

  private def requestEntry(request: Request, startMillis: Long): Map[String, Any] =
    val b = Map.newBuilder[String, Any]
    b += "time"            -> startMillis / 1000
    b += "event_timestamp" -> Instant.ofEpochMilli(startMillis).toString
    b += "method"          -> request.method.name
    b += "path"            -> request.path
    b += "uri"             -> request.uri
    request.query.foreach(q => b += "query_string" -> q)
    b += "request_size" -> request.content.length
    request.attribute(RequestAttribute.RemoteAddress).foreach(a => b += "remote_address" -> a)
    request.host.foreach(h => b += "host" -> h)
    request.userAgent.foreach(ua => b += "user_agent" -> ua)
    b += "request_header" -> headerEntry(request.headers)
    b.result()

  private def statusEntry(status: HttpStatus, startNanos: Long): Map[String, Any] = Map(
    "status_code"      -> status.code,
    "status_code_name" -> status.reason,
    "response_time_ms" -> elapsedMillis(startNanos)
  )

  // A failing application callback must not fail the request it logs
  private def extraEntries(request: Request, response: Option[Response]): Map[String, Any] =
    try
      config.extraEntries(request, response)
    catch
      case NonFatal(e) =>
        HttpAccessLogFilter.logger.warn(s"extraEntries failed: ${e}")
        Map.empty

  private def responseEntry(response: Response, startNanos: Long): Map[String, Any] = Map(
    "status_code"      -> response.status.code,
    "status_code_name" -> response.status.reason,
    "response_time_ms" -> elapsedMillis(startNanos),
    "response_size"    -> response.content.length,
    "response_header"  -> headerEntry(response.headers)
  )

  private def exceptionEntry(e: Throwable): Map[String, Any] =
    Map("exception_class" -> e.getClass.getName) ++
      Option(e.getMessage).map(m => "exception_message" -> m)

  private def headerEntry(headers: HttpMultiMap): Map[String, String] = headers
    .entries
    .groupMap(_._1.toLowerCase)(_._2)
    .flatMap {
      case (name, _) if config.excludedHeaders.contains(name) =>
        None
      case (name, _) if config.redactedHeaders.contains(name) =>
        Some(name -> "[redacted]")
      case (name, values) =>
        Some(name -> values.mkString(", "))
    }

  private def elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1000000

  // A failing log sink must not fail the request
  private def write(w: HttpAccessLogWriter, entry: Map[String, Any]): Unit =
    try
      w.write(entry)
    catch
      case NonFatal(e) =>
        HttpAccessLogFilter.logger.warn(s"Failed to write an HTTP access log entry: ${e}")

end HttpAccessLogFilter

object HttpAccessLogFilter:
  private val logger = wvlet.uni.log.Logger.of[HttpAccessLogFilter]

  /**
    * Add a field to the access log entry of this request, e.g. the user id from an authentication
    * filter or a model name from a controller. The access log filter runs outside them and does not
    * see the attributes they set on the request, so this is how they reach its entry. Does nothing
    * when no [[HttpAccessLogFilter]] handles the request.
    */
  def addEntry(request: Request, key: String, value: Any): Unit = request
    .attribute(ContextKey)
    .foreach(_.add(key, value))

  private[http] class LogContext:
    private val fields = mutable.LinkedHashMap.empty[String, Any]

    def add(key: String, value: Any): Unit = synchronized(fields.update(key, value))
    def entries: Map[String, Any]          = synchronized(fields.toMap)

  private[http] val ContextKey: RequestAttribute[LogContext] = RequestAttribute("httpAccessLog")

/**
  * A sink for [[HttpAccessLogFilter]] entries. Implement `write` to send entries elsewhere, e.g. to
  * Fluentd with its client library.
  */
trait HttpAccessLogWriter extends AutoCloseable:
  def write(entry: Map[String, Any]): Unit
  override def close(): Unit = {}

object HttpAccessLogWriter:

  /**
    * Write entries as JSON lines to a file, rotated daily and by size as configured. The file name
    * and rotation settings come from `config`; its formatter is ignored.
    */
  def jsonFile(config: FileLogHandlerConfig): HttpAccessLogWriter = JsonFileWriter(config)

  /** Keep entries in memory, e.g. for tests */
  def inMemory(): InMemoryHttpAccessLogWriter = InMemoryHttpAccessLogWriter()

  /** Encode an entry as one line of JSON */
  def toJson(entry: Map[String, Any]): String = AnyWeaver.default.toJson(entry)

  private class JsonFileWriter(config: FileLogHandlerConfig) extends HttpAccessLogWriter:
    private val handler = FileLogHandler(config.withFormatter(MessageOnlyFormatter))

    override def write(entry: Map[String, Any]): Unit = handler.publish(
      LogRecord(LogLevel.INFO, None, toJson(entry), None)
    )

    override def close(): Unit = handler.close()

  private object MessageOnlyFormatter extends LogFormatter:
    override def formatLog(r: LogRecord): String = r.getMessage

end HttpAccessLogWriter

class InMemoryHttpAccessLogWriter extends HttpAccessLogWriter:
  private val buffer = mutable.ArrayBuffer.empty[Map[String, Any]]

  override def write(entry: Map[String, Any]): Unit = synchronized(buffer += entry)
  def entries: Seq[Map[String, Any]]                = synchronized(buffer.toSeq)
  def clear(): Unit                                 = synchronized(buffer.clear())
