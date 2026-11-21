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

import wvlet.uni.rx.Rx

import java.util.concurrent.atomic.AtomicBoolean

/**
  * The span and duration metric of one operation, e.g. an MCP request or an LLM call. Attributes go
  * on the span; those set with [[setDimension]] also go on the duration metric, so they must be
  * low-cardinality. [[end]] has an effect once.
  */
private[uni] class OperationTrace(
    telemetry: Telemetry,
    val span: Span,
    duration: HistogramInstrument
):
  // Read the clock only when a metric will use it
  private val startNanos =
    if telemetry.isMetricsEnabled then
      telemetry.clock.nanoTime
    else
      0L

  private val ended = AtomicBoolean(false)

  @volatile
  private var dimensions = Map.empty[String, AttributeValue]

  /** The attributes of the duration metric so far. */
  protected def metricAttributes: Map[String, AttributeValue] = dimensions

  /** Set a span attribute. */
  def setAttribute(key: String, value: AttributeValue): Unit = OperationTrace.set(span, key, value)

  /** Set an attribute on the span and on the duration metric. */
  def setDimension(key: String, value: AttributeValue): Unit =
    setAttribute(key, value)
    synchronized {
      dimensions = dimensions.updated(key, value)
    }

  /**
    * Set `server.address` and `server.port` from an absolute URL, with the scheme's default port
    * when the URL names none.
    */
  def setServer(uri: String): Unit = OperationTrace
    .authorityOf(uri)
    .foreach { authority =>
      val (host, port) = OperationTrace.splitHostPort(authority)
      setDimension("server.address", host)
      port
        .orElse(
          if uri.startsWith("https://") then
            Some(443)
          else if uri.startsWith("http://") then
            Some(80)
          else
            None
        )
        .foreach(p => setDimension("server.port", p.toLong))
    }

  /** Mark the operation as failed with `errorType`, without ending it. */
  def setError(errorType: String, message: Option[String] = None): Unit =
    setDimension("error.type", errorType)
    span.setStatus(SpanStatus.Error, message)

  /** Record a failure and end the operation. */
  def fail(e: Throwable): Unit =
    setDimension("error.type", errorTypeOf(e))
    span.recordException(e)
    end()

  /** The `error.type` of an exception: its class name unless a subclass knows better. */
  protected def errorTypeOf(e: Throwable): String = e.getClass.getName

  /** Called once, before the span ends and the duration is recorded. */
  protected def onEnd(): Unit = ()

  /** End the span and record the duration, once. */
  def end(): Unit =
    if ended.compareAndSet(false, true) then
      onEnd()
      span.end()
      if telemetry.isMetricsEnabled then
        telemetry.recordHistogram(
          duration,
          (telemetry.clock.nanoTime - startNanos) / 1e9,
          dimensions
        )

  /** End when `events` completes, fails, or is cancelled. */
  def endWith[A](events: Rx[A]): Rx[A] =
    OperationTrace.endWithStream(events) {
      case Some(e) =>
        fail(e)
      case None =>
        end()
    }

end OperationTrace

private[uni] object OperationTrace:
  def set(span: Span, key: String, value: AttributeValue): Unit =
    value match
      case s: String =>
        span.setAttribute(key, s)
      case l: Long =>
        span.setAttribute(key, l)
      case d: Double =>
        span.setAttribute(key, d)
      case b: Boolean =>
        span.setAttribute(key, b)

  /** Split a `Host` header or URI authority into host and port, keeping IPv6 brackets off. */
  def splitHostPort(authority: String): (String, Option[Int]) =
    val colon        = authority.lastIndexOf(':')
    val close        = authority.lastIndexOf(']')
    val (host, port) =
      if colon > close && colon >= 0 then
        (authority.substring(0, colon), authority.substring(colon + 1).toIntOption)
      else
        (authority, None)
    (host.stripPrefix("[").stripSuffix("]"), port)

  // The host[:port] of an absolute URL, without user info
  private def authorityOf(uri: String): Option[String] =
    val sep = uri.indexOf("://")
    Option.when(sep >= 0) {
      val rest = uri.substring(sep + 3)
      val end  =
        rest.indexWhere(c => c == '/' || c == '?' || c == '#') match
          case -1 =>
            rest.length
          case i =>
            i
      val authority = rest.substring(0, end)
      authority.substring(authority.lastIndexOf('@') + 1)
    }

  /** Run `onEnd` once `events` completes (None), fails (Some), or is cancelled (None). */
  def endWithStream[A](events: Rx[A])(onEnd: Option[Throwable] => Unit): Rx[A] =
    val onCompletion: Rx[A] = Rx
      .single(())
      .flatMap { _ =>
        onEnd(None)
        Rx.empty[A]
      }
    events.tapOnFailure(e => onEnd(Some(e))).concat(onCompletion).onCancel(onEnd(None))

end OperationTrace
