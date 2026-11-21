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

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

/** A span attribute value: the scalar types OpenTelemetry attributes allow. */
type AttributeValue = String | Long | Double | Boolean

/** The role of a span, as in OpenTelemetry. */
enum SpanKind:
  case Internal,
    Server,
    Client

/** The outcome of a span, as in OpenTelemetry. `Unset` means the span ended without an error. */
enum SpanStatus:
  case Unset,
    Ok,
    Error

/** A timestamped annotation on a span, e.g. a retried attempt or an exception. */
case class SpanEvent(
    name: String,
    epochNanos: Long,
    attributes: Map[String, AttributeValue] = Map.empty
)

/** An ended span, as handed to a [[SpanExporter]]. */
case class SpanData(
    context: TraceContext,
    parentSpanId: Option[String],
    name: String,
    kind: SpanKind,
    startEpochNanos: Long,
    endEpochNanos: Long,
    attributes: Map[String, AttributeValue],
    events: Seq[SpanEvent],
    status: SpanStatus,
    statusMessage: Option[String]
):
  def traceId: String        = context.traceId
  def spanId: String         = context.spanId
  def durationNanos: Long    = endEpochNanos - startEpochNanos
  def durationMillis: Double = durationNanos / 1e6

/**
  * A unit of work in a trace. Attributes, events, and status can be set until [[end]]; later calls
  * do nothing. Attribute names follow the OpenTelemetry semantic conventions, e.g.
  * `http.request.method` or `rpc.method`.
  *
  * A span that isn't sampled records nothing but still carries a [[TraceContext]], so the trace
  * keeps propagating to downstream services.
  */
trait Span:
  def context: TraceContext
  def isRecording: Boolean

  def updateName(name: String): Span
  def setAttribute(key: String, value: String): Span
  def setAttribute(key: String, value: Long): Span
  def setAttribute(key: String, value: Double): Span
  def setAttribute(key: String, value: Boolean): Span
  def addEvent(name: String, attributes: Map[String, AttributeValue] = Map.empty): Span
  def setStatus(status: SpanStatus, message: Option[String] = None): Span

  /**
    * Record an exception as an `exception` event and mark the span as failed. The exception's
    * message is recorded; its stack trace is not, since it can be large and leak internals.
    */
  def recordException(e: Throwable): Span

  /** End the span and hand it to the exporter. Only the first call has an effect. */
  def end(): Unit

object Span:
  /** A span that records nothing and carries `context` for propagation. */
  def nonRecording(context: TraceContext): Span = NonRecordingSpan(context)

private[telemetry] class NonRecordingSpan(val context: TraceContext) extends Span:
  override def isRecording: Boolean                                                  = false
  override def updateName(name: String): Span                                        = this
  override def setAttribute(key: String, value: String): Span                        = this
  override def setAttribute(key: String, value: Long): Span                          = this
  override def setAttribute(key: String, value: Double): Span                        = this
  override def setAttribute(key: String, value: Boolean): Span                       = this
  override def addEvent(name: String, attributes: Map[String, AttributeValue]): Span = this
  override def setStatus(status: SpanStatus, message: Option[String]): Span          = this
  override def recordException(e: Throwable): Span                                   = this
  override def end(): Unit                                                           = ()

private[telemetry] class RecordingSpan(
    val context: TraceContext,
    parentSpanId: Option[String],
    initialName: String,
    kind: SpanKind,
    startEpochNanos: Long,
    startNanoTime: Long,
    clock: TelemetryClock,
    exporter: SpanExporter
) extends Span:
  private var name                          = initialName
  private val attributes                    = mutable.LinkedHashMap.empty[String, AttributeValue]
  private val events                        = mutable.ArrayBuffer.empty[SpanEvent]
  private var status                        = SpanStatus.Unset
  private var statusMessage: Option[String] = None
  private val ended                         = AtomicBoolean(false)

  override def isRecording: Boolean = !ended.get()

  private def update(body: => Unit): Span =
    synchronized {
      if !ended.get() then
        body
    }
    this

  override def updateName(name: String): Span = update(this.name = name)

  override def setAttribute(key: String, value: String): Span  = update(attributes(key) = value)
  override def setAttribute(key: String, value: Long): Span    = update(attributes(key) = value)
  override def setAttribute(key: String, value: Double): Span  = update(attributes(key) = value)
  override def setAttribute(key: String, value: Boolean): Span = update(attributes(key) = value)

  override def addEvent(name: String, attributes: Map[String, AttributeValue]): Span = update(
    events += SpanEvent(name, now, attributes)
  )

  override def setStatus(status: SpanStatus, message: Option[String]): Span = update {
    // Ok is final, as in OpenTelemetry: a later Error doesn't override an explicit Ok
    if this.status != SpanStatus.Ok then
      this.status = status
      this.statusMessage = message
  }

  override def recordException(e: Throwable): Span = update {
    val attrs = Map.newBuilder[String, AttributeValue]
    attrs += "exception.type" -> e.getClass.getName
    Option(e.getMessage).foreach(m => attrs += "exception.message" -> m)
    events += SpanEvent("exception", now, attrs.result())
    if status != SpanStatus.Ok then
      status = SpanStatus.Error
      statusMessage = Option(e.getMessage)
  }

  private def now: Long = startEpochNanos + (clock.nanoTime - startNanoTime)

  override def end(): Unit =
    val data = synchronized {
      if ended.compareAndSet(false, true) then
        Some(
          SpanData(
            context,
            parentSpanId,
            name,
            kind,
            startEpochNanos,
            now,
            attributes.toMap,
            events.toSeq,
            status,
            statusMessage
          )
        )
      else
        None
    }
    data.foreach(exporter.exportSpan)

end RecordingSpan

/** Time source for spans: wall-clock start times, monotonic durations. Replaceable in tests. */
trait TelemetryClock:
  def epochNanos: Long
  def nanoTime: Long

object TelemetryClock:
  val system: TelemetryClock =
    new TelemetryClock:
      override def epochNanos: Long = System.currentTimeMillis() * 1000000L
      override def nanoTime: Long   = System.nanoTime()
