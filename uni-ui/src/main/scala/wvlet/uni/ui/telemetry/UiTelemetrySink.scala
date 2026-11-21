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
package wvlet.uni.ui.telemetry

import org.scalajs.dom
import wvlet.uni.http.UiTelemetryRelay
import wvlet.uni.json.JSON.*
import wvlet.uni.telemetry.AttributeValue

import scala.scalajs.js

/** Where [[UiTelemetry]] sends its batches. */
trait UiTelemetrySink:
  /**
    * Send one batch. `unloading` is true when the page is being hidden or closed, so the sink must
    * not wait for anything.
    */
  def send(batch: UiEventBatch, unloading: Boolean): Unit

object UiTelemetrySink:
  /** Calls `f` with each batch, e.g. to hand events to another analytics SDK. */
  def apply(f: UiEventBatch => Unit): UiTelemetrySink = (batch, _) => f(batch)

  /** Sends every batch to each sink in order. */
  def all(sinks: UiTelemetrySink*): UiTelemetrySink =
    (batch, unloading) => sinks.foreach(_.send(batch, unloading))

  /**
    * Posts batches as JSON to a [[wvlet.uni.http.UiTelemetryRelay]] on the page's own server, which
    * passes them to the service's telemetry (and so to OTLP `/v1/logs`). Uses `fetch` with
    * `keepalive`, so the last batch is delivered even while the page closes; batches are split to
    * stay under the browser's 64 KiB keepalive limit.
    */
  def relay(path: String = UiTelemetryRelay.DefaultPath): UiTelemetrySink = RelaySink(path)

  /**
    * Reports each event as a Datadog RUM custom action through the Datadog browser SDK, named
    * `ui.press button:save` (the event and its ref or command id), with the attributes as context.
    * Pass the SDK object when it is imported from npm (`datadogRum`); by default it uses the
    * `window.DD_RUM` global of the CDN script. Does nothing when the SDK is not on the page.
    */
  def datadogRum(rum: => js.UndefOr[js.Dynamic] = DatadogRumSink.global): UiTelemetrySink =
    DatadogRumSink(() => rum)

  /** The JSON `UiTelemetryRelay` accepts. */
  def toJson(batch: UiEventBatch): JSONObject = JSONObject(
    Seq(
      "session" -> JSONString(batch.sessionId),
      "events"  ->
        JSONArray(
          batch
            .events
            .map { e =>
              JSONObject(
                Seq(
                  "name"       -> JSONString(e.name),
                  "time"       -> JSONLong(e.timeMillis),
                  "attributes" -> JSONObject(e.attributes.toSeq.map((k, v) => k -> jsonValue(v)))
                )
              )
            }
            .toIndexedSeq
        )
    )
  )

  // Long before Double: on Scala.js every number would match Double
  private def jsonValue(v: AttributeValue): JSONValue =
    v match
      case s: String =>
        JSONString(s)
      case l: Long =>
        JSONLong(l)
      case d: Double =>
        JSONDouble(d)
      case b: Boolean =>
        JSONBoolean(b)

end UiTelemetrySink

private class RelaySink(path: String) extends UiTelemetrySink:
  // Each request stays under the relay's limits, in UTF-8 bytes and events
  private val MaxBodyBytes = 48 * 1024
  private val MaxEvents    = UiTelemetryRelay.DefaultMaxEvents
  // fetch keepalive allows 64 KiB in flight per page across all requests; leave room for headers
  private val KeepaliveBudget = 60 * 1024

  override def send(batch: UiEventBatch, unloading: Boolean): Unit =
    var keepaliveBytes = 0
    chunks(batch).foreach { body =>
      val bytes = RelaySink.utf8Length(body)
      // Requests past the shared budget go without keepalive: they finish unless the page closes
      val keepalive = keepaliveBytes + bytes <= KeepaliveBudget
      if keepalive then
        keepaliveBytes += bytes
      val init = js
        .Dynamic
        .literal(
          method = "POST",
          body = body,
          keepalive = keepalive,
          credentials = "same-origin",
          headers = js.Dynamic.literal("Content-Type" -> "application/json")
        )
      val p = js.Dynamic.global.fetch(path, init)
      // A failed send is dropped: telemetry never surfaces errors to the page
      p.`catch`((_: js.Any) => ())
    }

  /** The batch as JSON request bodies, each within the relay's byte and event limits. */
  private[telemetry] def chunks(batch: UiEventBatch): Seq[String] =
    val out          = Seq.newBuilder[String]
    var current      = Vector.empty[UiEvent]
    var size         = 0
    def emit(): Unit =
      if current.nonEmpty then
        out += UiTelemetrySink.toJson(UiEventBatch(batch.sessionId, current)).toJSON
        current = Vector.empty
        size = 0
    batch
      .events
      .foreach { e =>
        val n = RelaySink.utf8Length(
          UiTelemetrySink.toJson(UiEventBatch(batch.sessionId, Seq(e))).toJSON
        )
        if size + n > MaxBodyBytes || current.size >= MaxEvents then
          emit()
        current :+= e
        size += n
      }
    emit()
    out.result()

end RelaySink

private object RelaySink:
  def utf8Length(s: String): Int =
    var n = 0
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c < 0x80 then
        n += 1
      else if c < 0x800 then
        n += 2
      else if Character.isHighSurrogate(c) && i + 1 < s.length then
        n += 4
        i += 1
      else
        n += 3
      i += 1
    n

private class DatadogRumSink(rum: () => js.UndefOr[js.Dynamic]) extends UiTelemetrySink:
  override def send(batch: UiEventBatch, unloading: Boolean): Unit = rum()
    .toOption
    .filter(r => !js.isUndefined(r.selectDynamic("addAction")))
    .foreach { r =>
      batch
        .events
        .foreach { e =>
          val subject = e
            .ref
            .orElse(
              e.attributes
                .get("command.id")
                .collect { case s: String =>
                  s
                }
            )
          val context = js.Dictionary.empty[js.Any]
          e.attributes
            .foreach { (k, v) =>
              context(k) =
                v match
                  case s: String =>
                    s
                  case l: Long =>
                    l.toDouble
                  case d: Double =>
                    d
                  case b: Boolean =>
                    b
            }
          context("session.id") = batch.sessionId
          r.addAction(subject.fold(e.name)(s => s"${e.name} ${s}"), context)
        }
    }

end DatadogRumSink

private object DatadogRumSink:
  def global: js.UndefOr[js.Dynamic] =
    val g = js.Dynamic.global.selectDynamic("DD_RUM")
    if js.isUndefined(g) then
      js.undefined
    else
      g
