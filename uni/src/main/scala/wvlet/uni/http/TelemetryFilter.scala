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

import wvlet.uni.rx.Rx
import wvlet.uni.telemetry.{
  AttributeValue,
  HistogramInstrument,
  Span,
  SpanKind,
  SpanStatus,
  Telemetry,
  TraceContext
}

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
  * Traces each request as a server span, following the OpenTelemetry HTTP and RPC semantic
  * conventions. A request with a W3C `traceparent` header continues the caller's trace.
  *
  * The span is named by the RPC method (`UserService/getUser`) or the route (`GET /users/:id`) once
  * the router resolves it, else by the HTTP method. It ends when the response is ready, or when an
  * event stream ends. While the handler runs its synchronous part, the span is
  * [[Telemetry.currentSpan]], so outgoing calls from an HTTP client with `withTelemetry` become its
  * children. Handlers can also read it from the [[TelemetryFilter.CurrentSpan]] attribute.
  *
  * Add it as the outermost filter, next to [[HttpAccessLogFilter]], so the span covers the other
  * filters. With a disabled [[Telemetry]] it passes requests through untouched. WebSocket upgrades
  * don't run filters and get no span.
  */
class TelemetryFilter(telemetry: Telemetry) extends RxHttpFilter:

  override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    if !telemetry.isEnabled then
      next.handle(request)
    else
      traced(request, next)

  private def traced(request: Request, next: RxHttpHandler): Rx[Response] =
    val span = telemetry.startSpan(
      request.method.name,
      SpanKind.Server,
      TelemetryFilter.traceContextOf(request)
    )
    span.setAttribute("http.request.method", request.method.name)
    span.setAttribute("url.path", request.path)
    // The query string is left out: it often carries tokens
    request.host.foreach(TelemetryFilter.setServerAddress(span, _))
    request.userAgent.foreach(ua => span.setAttribute("user_agent.original", ua))
    request
      .attribute(RequestAttribute.RemoteAddress)
      .foreach(a => span.setAttribute("client.address", a))
    val call = TelemetryFilter.ServerCall(telemetry, span, request.method.name)

    val rx =
      try
        Telemetry.withSpan(span) {
          next.handle(
            request
              .withAttribute(TelemetryFilter.CurrentSpan, span)
              .withAttribute(TelemetryFilter.CallKey, call)
          )
        }
      catch
        case NonFatal(e) =>
          Rx.exception(e)

    // The server answers only the first response; a failure after it must not end the call twice
    val answered  = AtomicBoolean(false)
    val responses = rx.transformTry {
      case Success(response) =>
        if answered.compareAndSet(false, true) then
          call.setStatus(response.status)
          if response.isEventStream then
            Success(response.withEvents(TelemetryFilter.endWithStream(response.events)(call.end)))
          else
            call.end(None)
            Success(response)
        else
          Success(response)
      case Failure(e) =>
        if answered.compareAndSet(false, true) then
          call.setStatus(HttpStatus.InternalServerError_500)
          call.end(Some(e))
        Failure(e)
    }
    // A handler Rx that completes without a response is answered with 404 by the server
    val onEmpty: Rx[Response] = Rx
      .single(())
      .flatMap { _ =>
        if answered.compareAndSet(false, true) then
          call.setStatus(HttpStatus.NotFound_404)
          call.end(None)
        Rx.empty[Response]
      }
    responses.concat(onEmpty).onCancel(call.end(None))

  end traced

end TelemetryFilter

object TelemetryFilter:
  /** The server span of the request, set by [[TelemetryFilter]]. */
  val CurrentSpan: RequestAttribute[Span] = RequestAttribute("telemetry.span")

  /** The RPC system name reported as `rpc.system`. */
  val RPCSystem: String = "uni"

  def apply(telemetry: Telemetry): TelemetryFilter = new TelemetryFilter(telemetry)

  /** The span and the duration metric of one request. */
  private[http] class ServerCall(telemetry: Telemetry, val span: Span, method: String):
    private val startNanos = telemetry.clock.nanoTime
    private val ended      = AtomicBoolean(false)
    private val attributes = mutable.LinkedHashMap[String, AttributeValue](
      "http.request.method" -> method
    )

    private def metric(key: String, value: AttributeValue): Unit = synchronized(attributes(key) =
      value
    )

    def recordRPC(serviceName: String, methodName: String): Unit =
      span
        .updateName(s"${serviceName}/${methodName}")
        .setAttribute("rpc.system", RPCSystem)
        .setAttribute("rpc.service", serviceName)
        .setAttribute("rpc.method", methodName)
      metric("rpc.system", RPCSystem)
      metric("rpc.service", serviceName)
      metric("rpc.method", methodName)

    def recordRoute(pathPattern: String, controllerName: String, methodName: String): Unit =
      span
        .updateName(s"${method} ${pathPattern}")
        .setAttribute("http.route", pathPattern)
        .setAttribute("code.function.name", s"${controllerName}.${methodName}")
      metric("http.route", pathPattern)

    def setStatus(status: HttpStatus): Unit =
      span.setAttribute("http.response.status_code", status.code.toLong)
      metric("http.response.status_code", status.code.toLong)
      // Server spans fail only on 5xx: a 4xx is the client's error, not the server's
      if status.code >= 500 then
        span.setAttribute("error.type", status.code.toString)
        span.setStatus(SpanStatus.Error)
        metric("error.type", status.code.toString)

    /** End the span and record the duration, once. */
    def end(error: Option[Throwable]): Unit =
      if ended.compareAndSet(false, true) then
        error.foreach { e =>
          span.recordException(e)
          metric("error.type", e.getClass.getName)
        }
        span.end()
        val seconds = (telemetry.clock.nanoTime - startNanos) / 1e9
        telemetry.recordHistogram(
          HistogramInstrument.HttpServerRequestDuration,
          seconds,
          synchronized(attributes.toMap)
        )

  end ServerCall

  private[http] val CallKey: RequestAttribute[ServerCall] = RequestAttribute("telemetry.call")

  /** Name the server span after the RPC method. Called by the RPC dispatcher. */
  private[http] def recordRPC(request: Request, serviceName: String, methodName: String): Unit =
    request.attribute(CallKey).foreach(_.recordRPC(serviceName, methodName))

  /** Name the server span after the matched route. Called by the router. */
  private[http] def recordRoute(
      request: Request,
      pathPattern: String,
      controllerName: String,
      methodName: String
  ): Unit = request
    .attribute(CallKey)
    .foreach(_.recordRoute(pathPattern, controllerName, methodName))

  /** The trace context of a `traceparent` header (and its `tracestate`) on `request`. */
  private[http] def traceContextOf(request: Request): Option[TraceContext] = request
    .header(TraceContext.TraceParentHeader)
    .flatMap(TraceContext.parse(_, request.header(TraceContext.TraceStateHeader)))

  /** Set `server.address` and `server.port` from a `host:port` authority. */
  private[http] def setServerAddress(span: Span, authority: String): Unit =
    val (host, port) = splitHostPort(authority)
    span.setAttribute("server.address", host)
    port.foreach(p => span.setAttribute("server.port", p.toLong))

  /** Split a `Host` header or URI authority into host and port, keeping IPv6 brackets off. */
  private[http] def splitHostPort(authority: String): (String, Option[Int]) = wvlet
    .uni
    .telemetry
    .OperationTrace
    .splitHostPort(authority)

  /** Call `onEnd` once when `events` completes, fails, or is cancelled. */
  private[http] def endWithStream[A](events: Rx[A])(onEnd: Option[Throwable] => Unit): Rx[A] =
    wvlet.uni.telemetry.OperationTrace.endWithStream(events)(onEnd)

end TelemetryFilter
