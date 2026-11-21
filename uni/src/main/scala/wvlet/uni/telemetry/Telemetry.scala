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

import scala.util.control.NonFatal

/**
  * Starts spans and sends them to an exporter. Uni's HTTP server
  * ([[wvlet.uni.http.TelemetryFilter]]) and HTTP client (`HttpClientConfig.withTelemetry`) use it
  * to trace requests with no code in the handlers.
  *
  * `Telemetry()` with no exporter is disabled: it starts no spans and costs nothing per request.
  * {{{
  * val telemetry = Telemetry()
  *   .withServiceName("orders")
  *   .withExporter(SpanExporter.log())
  * }}}
  *
  * @param serviceName
  *   reported as the `service.name` resource attribute
  * @param sampleRatio
  *   the fraction of new traces to record, from 0.0 to 1.0. A request that arrives with a
  *   `traceparent` follows the caller's sampled flag instead
  */
case class Telemetry(
    serviceName: String = "unknown_service",
    exporter: SpanExporter = SpanExporter.none,
    sampleRatio: Double = 1.0,
    resourceAttributes: Map[String, AttributeValue] = Map.empty,
    clock: TelemetryClock = TelemetryClock.system
) extends LogSupport:
  require(
    sampleRatio >= 0.0 && sampleRatio <= 1.0,
    s"sampleRatio must be in [0, 1]: ${sampleRatio}"
  )

  def withServiceName(name: String): Telemetry        = copy(serviceName = name)
  def withExporter(exporter: SpanExporter): Telemetry = copy(exporter = exporter)
  def noExporter: Telemetry                           = copy(exporter = SpanExporter.none)
  def withSampleRatio(ratio: Double): Telemetry       = copy(sampleRatio = ratio)
  def withResourceAttribute(key: String, value: AttributeValue): Telemetry = copy(
    resourceAttributes = resourceAttributes.updated(key, value)
  )

  def withClock(clock: TelemetryClock): Telemetry = copy(clock = clock)

  /** False when no exporter is set; then no spans are started. */
  def isEnabled: Boolean = exporter ne SpanExporter.none

  /** The resource attributes every span of this service carries, including `service.name`. */
  def resource: Map[String, AttributeValue] = resourceAttributes.updated(
    "service.name",
    serviceName
  )

  /**
    * Start a span. With no `parent`, the current span ([[Telemetry.currentSpan]]) is the parent;
    * with none of either, the span starts a new trace, sampled at `sampleRatio`.
    */
  def startSpan(
      name: String,
      kind: SpanKind = SpanKind.Internal,
      parent: Option[TraceContext] = Telemetry.currentContext
  ): Span =
    if !isEnabled then
      // No ids, no random draws: a disabled instance passes the parent through, if any
      parent.map(Span.nonRecording).getOrElse(Telemetry.disabledSpan)
    else
      startEnabledSpan(name, kind, parent)

  private def startEnabledSpan(name: String, kind: SpanKind, parent: Option[TraceContext]): Span =
    val context =
      parent match
        case Some(p) =>
          p.newChild
        case None =>
          TraceContext.newRoot(sampled = sample())
    if !context.sampled then
      Span.nonRecording(context)
    else
      RecordingSpan(
        context,
        parent.map(_.spanId),
        name,
        kind,
        clock.epochNanos,
        clock.nanoTime,
        clock,
        safeExporter
      )

  /** Run `body` in a new span, which ends when `body` returns or throws. */
  def trace[A](name: String, kind: SpanKind = SpanKind.Internal)(body: Span => A): A =
    val span = startSpan(name, kind)
    try
      Telemetry.withSpan(span)(body(span))
    catch
      case e: Throwable =>
        span.recordException(e)
        throw e
    finally
      span.end()

  private def sample(): Boolean =
    sampleRatio >= 1.0 || (sampleRatio > 0.0 && scala.util.Random.nextDouble() < sampleRatio)

  // Telemetry must never fail the request it observes
  private lazy val safeExporter: SpanExporter =
    val underlying = exporter
    (span: SpanData) =>
      try
        underlying.exportSpan(span)
      catch
        case NonFatal(e) =>
          warn(s"Failed to export span ${span.name}: ${e.getMessage}")

end Telemetry

object Telemetry:
  /** A disabled instance: starts no spans. */
  val noop: Telemetry = Telemetry()

  private val current = ThreadLocal[Span]()

  // The span of a disabled instance with no parent. Its all-zero ids are invalid, so it is never
  // propagated: only enabled clients send traceparent
  private val disabledSpan: Span = Span.nonRecording(
    TraceContext("0" * 32, "0" * 16, sampled = false)
  )

  /**
    * The span running on this thread, if any. The HTTP server sets it while a handler runs its
    * synchronous part, so an RPC method that returns a plain value sees it. Code that continues in
    * an `Rx` callback on another thread does not; pass the span explicitly there, e.g. through the
    * request's [[wvlet.uni.http.TelemetryFilter.CurrentSpan]] attribute.
    */
  def currentSpan: Option[Span] = Option(current.get())

  /** The context of [[currentSpan]]. */
  def currentContext: Option[TraceContext] = currentSpan.map(_.context)

  /** Run `body` with `span` as the current span of this thread. */
  def withSpan[A](span: Span)(body: => A): A =
    val saved = current.get()
    current.set(span)
    try body
    finally
      if saved == null then
        current.remove()
      else
        current.set(saved)

  /**
    * Run `body` with `context` as the parent of spans started on this thread, e.g. a context read
    * from a message queue.
    */
  def withContext[A](context: TraceContext)(body: => A): A =
    withSpan(Span.nonRecording(context))(body)

end Telemetry
