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

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.Rx
import wvlet.uni.telemetry.{AttributeValue, Telemetry}

import scala.util.control.NonFatal

/**
  * Receives UI events that `UiTelemetry` (uni-ui) posts from the browser, and passes them to the
  * service's [[wvlet.uni.telemetry.Telemetry]] as events, which `withOtlp` sends to `/v1/logs`. A
  * browser can't post OTLP to the Datadog Agent or a Collector itself: they don't accept a page's
  * origin and shouldn't face the internet.
  *
  * The endpoint is open to anyone who can reach the page, so it accepts only what `UiTelemetry`
  * sends and drops the rest of the request:
  *   - `POST` with an `application/json` body of at most `maxBodyBytes`, and at most `maxEvents`
  *     events. Browsers send that content type cross-site only after a CORS preflight, which the
  *     relay doesn't answer;
  *   - an `Origin` header, when present, whose host matches the request's `Host`;
  *   - event names under `ui.` or `command.`, attribute keys of lower-case letters, digits, `.`,
  *     and `_`, at most `maxAttributes` per event, and string values cut to `maxValueLength`.
  *
  * Requests to other paths pass through. Put an authentication or rate-limiting filter before it
  * when the page is public.
  * {{{
  * Http.server.withFilter(UiTelemetryRelay(telemetry)).withRxHandler(app)
  * }}}
  */
case class UiTelemetryRelay(
    telemetry: Telemetry,
    path: String = UiTelemetryRelay.DefaultPath,
    maxBodyBytes: Int = 64 * 1024,
    maxEvents: Int = UiTelemetryRelay.DefaultMaxEvents,
    maxAttributes: Int = 24,
    maxValueLength: Int = 256
) extends RxHttpFilter:
  def withPath(p: String): UiTelemetryRelay        = copy(path = p)
  def withMaxBodyBytes(n: Int): UiTelemetryRelay   = copy(maxBodyBytes = n)
  def withMaxEvents(n: Int): UiTelemetryRelay      = copy(maxEvents = n)
  def withMaxAttributes(n: Int): UiTelemetryRelay  = copy(maxAttributes = n)
  def withMaxValueLength(n: Int): UiTelemetryRelay = copy(maxValueLength = n)

  override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
    if request.path != path then
      next.handle(request)
    else if request.method != HttpMethod.POST then
      Rx.single(Response(HttpStatus.MethodNotAllowed_405).addHeader(HttpHeader.Allow, "POST"))
    else if request.content.length > maxBodyBytes then
      Rx.single(Response(HttpStatus.PayloadTooLarge_413))
    else if !request.contentType.exists(_.isJson) then
      // A cross-site page can post text/plain with no preflight, but not application/json
      Rx.single(Response(HttpStatus.UnsupportedMediaType_415))
    else if !UiTelemetryRelay.isSameOrigin(request) then
      Rx.single(Response(HttpStatus.Forbidden_403))
    else
      Rx.single(relay(request))

  private def relay(request: Request): Response =
    val parsed =
      try
        Some(JSON.parse(request.content.asString.getOrElse("")))
      catch
        case NonFatal(_) =>
          None
    parsed match
      case Some(body: JSONObject) =>
        body.get("events") match
          case Some(JSONArray(events)) if events.size <= maxEvents =>
            val common = Map.newBuilder[String, AttributeValue]
            body
              .get("session")
              .collect {
                case JSONString(s) if UiTelemetryRelay.isSessionId(s) =>
                  s
              }
              .foreach(s => common += "session.id" -> s)
            request
              .userAgent
              .foreach(ua => common += "user_agent.original" -> ua.take(maxValueLength))
            val shared = common.result()
            events.foreach {
              case e: JSONObject =>
                emit(e, shared)
              case _ =>
            }
            Response(HttpStatus.NoContent_204)
          case _ =>
            Response(HttpStatus.BadRequest_400)
      case _ =>
        Response(HttpStatus.BadRequest_400)

  end relay

  private def emit(e: JSONObject, shared: Map[String, AttributeValue]): Unit =
    e.get("name") match
      case Some(JSONString(name)) if UiTelemetryRelay.isEventName(name) =>
        val attrs = Map.newBuilder[String, AttributeValue]
        attrs ++= shared
        e.get("attributes") match
          case Some(JSONObject(fields)) =>
            fields
              .iterator
              .filter((k, _) => UiTelemetryRelay.isAttributeKey(k))
              .take(maxAttributes)
              .foreach { (k, v) =>
                v match
                  case JSONString(s) =>
                    attrs += k -> s.take(maxValueLength)
                  case JSONLong(l) =>
                    attrs += k -> l
                  case JSONDouble(d) if !d.isNaN && !d.isInfinite =>
                    attrs += k -> d
                  case JSONBoolean(b) =>
                    attrs += k -> b
                  case _ =>
              }
          case _ =>
        // The browser's clock: kept when it is a plausible epoch time, else the server's
        val nanos =
          e.get("time") match
            case Some(JSONLong(ms)) if ms > 0 && ms < 32503680000000L =>
              ms * 1000000L
            case _ =>
              telemetry.clock.epochNanos
        telemetry.emitEvent(name, attrs.result(), nanos)
      case _ =>

end UiTelemetryRelay

object UiTelemetryRelay:
  /** The path `UiTelemetry`'s relay sink posts to by default. */
  val DefaultPath: String = "/_uni/telemetry/ui"

  /** The most events in one request, by default; `UiTelemetry` splits its batches to fit. */
  val DefaultMaxEvents: Int = 200

  // Browsers send Origin on every cross-origin POST; a request without one isn't from another site
  private def isSameOrigin(request: Request): Boolean =
    request.header(HttpHeader.Origin) match
      case None =>
        true
      case Some(origin) =>
        val authority =
          origin.indexOf("://") match
            case -1 =>
              origin
            case i =>
              origin.substring(i + 3)
        request.host.exists(_.equalsIgnoreCase(authority))

  private def isEventName(s: String): Boolean =
    s.length <= 64 &&
      (s.startsWith("ui.") || s.startsWith("command.")) &&
      s.forall(c => (c >= 'a' && c <= 'z') || c == '.' || c == '_')

  private def isAttributeKey(s: String): Boolean =
    s.nonEmpty && s.length <= 64 && s.charAt(0) >= 'a' && s.charAt(0) <= 'z' &&
      s.forall(c =>
        (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') || c == '.' || c == '_'
      )

  private def isSessionId(s: String): Boolean =
    s.nonEmpty && s.length <= 32 && s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

end UiTelemetryRelay
