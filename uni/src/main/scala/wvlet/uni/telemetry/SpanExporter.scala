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
package wvlet.uni.telemetry

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx

import scala.collection.mutable
import scala.util.control.NonFatal

/** Lifecycle shared by span and metric exporters. */
trait TelemetryExporter extends AutoCloseable:
  override def close(): Unit = ()

  /**
    * Send what is buffered and stop. The returned Rx emits once done, so on the JVM and Scala
    * Native `shutdown().await` waits for the last send. The default just closes.
    */
  def shutdown(): Rx[Unit] = Rx.single(close())

/**
  * Receives each span once it ends. `exportSpan` runs on the thread that ended the span, so an
  * exporter that does I/O should buffer and send from its own thread.
  */
trait SpanExporter extends TelemetryExporter:
  def exportSpan(span: SpanData): Unit

object SpanExporter:
  /** Drops every span. A [[Telemetry]] with this exporter is disabled. */
  val none: SpanExporter = (_: SpanData) => ()

  /** Keeps ended spans in memory, for tests. */
  def inMemory(): InMemorySpanExporter = InMemorySpanExporter()

  /** Writes one line per ended span to the `wvlet.uni.telemetry.LogSpanExporter` logger. */
  def log(): SpanExporter = LogSpanExporter()

  /** Sends each span to every exporter in order. A failing exporter doesn't stop the others. */
  def all(exporters: SpanExporter*): SpanExporter = MultiSpanExporter(exporters.toSeq)

class InMemorySpanExporter extends SpanExporter:
  private val buffer = mutable.ArrayBuffer.empty[SpanData]

  override def exportSpan(span: SpanData): Unit = synchronized(buffer += span)

  /** The ended spans, in the order they ended. */
  def spans: Seq[SpanData] = synchronized(buffer.toSeq)
  def clear(): Unit        = synchronized(buffer.clear())

class LogSpanExporter extends SpanExporter with LogSupport:
  override def exportSpan(span: SpanData): Unit =
    val attrs = span.attributes.map((k, v) => s"${k}=${v}").mkString(" ")
    info(
      f"${span.name} ${span.kind} ${span.status} ${span.durationMillis}%.2fms trace=${span
          .traceId} span=${span.spanId} ${attrs}"
    )

private class MultiSpanExporter(exporters: Seq[SpanExporter]) extends SpanExporter with LogSupport:
  override def exportSpan(span: SpanData): Unit = exporters.foreach { e =>
    try
      e.exportSpan(span)
    catch
      case NonFatal(ex) =>
        warn(s"Span exporter ${e.getClass.getName} failed: ${ex.getMessage}")
  }

  override def shutdown(): Rx[Unit] =
    exporters.foldLeft(Rx.single(()))((acc, e) => acc.flatMap(_ => e.shutdown()))

  override def close(): Unit = exporters.foreach { e =>
    try
      e.close()
    catch
      case NonFatal(ex) =>
        warn(s"Span exporter ${e.getClass.getName} failed to close: ${ex.getMessage}")
  }
