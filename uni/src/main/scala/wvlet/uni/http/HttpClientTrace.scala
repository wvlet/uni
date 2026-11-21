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
import wvlet.uni.telemetry.{Span, SpanKind, SpanStatus, Telemetry, TraceContext}

/**
  * The client span of one logical HTTP call. Retries and redirects are attempts of the same call:
  * each attempt after the first is a span event, and the span takes the final response's status, as
  * in the OpenTelemetry HTTP client conventions for a span per call.
  */
private[http] class HttpClientTrace(val span: Span, val request: HttpRequest):
  private var attempts = 0

  /** Called before each attempt, including retries and redirects. */
  def onAttempt(): Unit =
    attempts += 1
    if attempts > 1 then
      span.addEvent(
        "http.request.resend",
        Map("http.request.resend_count" -> (attempts - 1).toLong)
      )

  /** Record the response status, without ending the span (a stream may still be running). */
  def onResponse(response: HttpResponse): Unit =
    val code = response.status.code
    span.setAttribute("http.response.status_code", code.toLong)
    // Client spans fail on any 4xx or 5xx, which the caller sees as an error
    if code >= 400 then
      span.setAttribute("error.type", code.toString)
      span.setStatus(SpanStatus.Error)

  def finish(response: HttpResponse): Unit =
    onResponse(response)
    end()

  def fail(e: Throwable): Unit =
    // A 4xx/5xx the client turned into an exception still reports its status code
    val response =
      e match
        case h: HttpException =>
          h.response
        case m: HttpMaxRetryException =>
          Some(m.response)
        case _ =>
          None
    response match
      case Some(r) =>
        onResponse(r)
      case None =>
        span.setAttribute("error.type", e.getClass.getName)
    span.recordException(e)
    end()

  def end(): Unit =
    if attempts > 1 then
      span.setAttribute("http.request.resend_count", (attempts - 1).toLong)
    span.end()

  /** End the span when `events` completes, fails, or is cancelled. */
  def endWithStream[A](events: Rx[A]): Rx[A] = TelemetryFilter.endWithStream(
    events.tapOnFailure(e => span.setAttribute("error.type", e.getClass.getName)),
    span
  )

end HttpClientTrace

private[http] object HttpClientTrace:
  /** A trace that records nothing, for clients without telemetry. */
  def none(request: HttpRequest): HttpClientTrace = HttpClientTrace(noSpan, request)

  // Never sent: a client without telemetry adds no traceparent header
  private val noSpan = Span.nonRecording(TraceContext("0" * 32, "0" * 16, sampled = false))

  /**
    * Start the client span of `request` and add its `traceparent` header. The parent is a
    * `traceparent` header the caller set on the request, else `parent`.
    */
  def start(
      telemetry: Telemetry,
      request: HttpRequest,
      parent: Option[TraceContext]
  ): HttpClientTrace =
    val span = telemetry.startSpan(
      request.method.name,
      SpanKind.Client,
      TelemetryFilter.traceContextOf(request).orElse(parent)
    )
    span.setAttribute("http.request.method", request.method.name)
    val (full, authority) = urlWithoutQuery(request.uri)
    span.setAttribute("url.full", full)
    authority.foreach(TelemetryFilter.setServerAddress(span, _))
    val ctx = span.context
    // Drop any tracestate the request carried: it belongs to the replaced traceparent
    var traced = request
      .removeHeader(TraceContext.TraceParentHeader)
      .removeHeader(TraceContext.TraceStateHeader)
      .addHeader(TraceContext.TraceParentHeader, ctx.toTraceParent)
    ctx
      .traceState
      .foreach { state =>
        traced = traced.addHeader(TraceContext.TraceStateHeader, state)
      }
    HttpClientTrace(span, traced)

  end start

  /**
    * The URI without its query, fragment, and user info, which can carry credentials, and the
    * authority (`host:port`) of an absolute URI.
    */
  private[http] def urlWithoutQuery(uri: String): (String, Option[String]) =
    val end = Seq(uri.indexOf('?'), uri.indexOf('#')).filter(_ >= 0).minOption.getOrElse(uri.length)
    val base = uri.substring(0, end)
    val sep  = base.indexOf("://")
    if sep < 0 then
      (base, None)
    else
      val authStart = sep + 3
      val pathStart =
        base.indexOf('/', authStart) match
          case -1 =>
            base.length
          case i =>
            i
      val authority = base.substring(authStart, pathStart)
      val hostPort  = authority.substring(authority.lastIndexOf('@') + 1)
      (s"${base.substring(0, authStart)}${hostPort}${base.substring(pathStart)}", Some(hostPort))

end HttpClientTrace
