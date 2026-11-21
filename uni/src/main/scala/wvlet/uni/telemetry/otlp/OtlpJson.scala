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
package wvlet.uni.telemetry.otlp

import wvlet.uni.json.JSON.*
import wvlet.uni.telemetry.*

/**
  * Encodes spans and metrics as OTLP/HTTP JSON request bodies
  * (https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding): lowerCamelCase field names,
  * hex trace and span ids, enums as integers, and 64-bit integers as strings.
  */
object OtlpJson:
  /** The instrumentation scope reported with every span and metric. */
  val ScopeName: String = "wvlet.uni"

  private val Delta = 1L // AGGREGATION_TEMPORALITY_DELTA

  /** An `ExportTraceServiceRequest` for `/v1/traces`. */
  def traces(spans: Seq[SpanData]): JSONObject = JSONObject(
    Seq(
      "resourceSpans" ->
        JSONArray(
          spans
            .groupBy(_.resource)
            .map { (resource, group) =>
              JSONObject(
                Seq(
                  "resource"   -> resourceJson(resource),
                  "scopeSpans" ->
                    JSONArray(
                      IndexedSeq(
                        JSONObject(
                          Seq("scope" -> scope, "spans" -> JSONArray(group.map(span).toIndexedSeq))
                        )
                      )
                    )
                )
              )
            }
            .toIndexedSeq
        )
    )
  )

  /** An `ExportMetricsServiceRequest` for `/v1/metrics`. */
  def metrics(histograms: Seq[HistogramData]): JSONObject = JSONObject(
    Seq(
      "resourceMetrics" ->
        JSONArray(
          histograms
            .groupBy(_.resource)
            .map { (resource, group) =>
              val metrics =
                group
                  .groupBy(_.instrument)
                  .map { (instrument, points) =>
                    histogram(instrument, points)
                  }
                  .toIndexedSeq
              JSONObject(
                Seq(
                  "resource"     -> resourceJson(resource),
                  "scopeMetrics" ->
                    JSONArray(
                      IndexedSeq(JSONObject(Seq("scope" -> scope, "metrics" -> JSONArray(metrics))))
                    )
                )
              )
            }
            .toIndexedSeq
        )
    )
  )

  private def scope: JSONObject = JSONObject(Seq("name" -> JSONString(ScopeName)))

  private def resourceJson(resource: Map[String, AttributeValue]): JSONObject = JSONObject(
    Seq("attributes" -> attributes(resource))
  )

  private def span(s: SpanData): JSONObject =
    val b = Seq.newBuilder[(String, JSONValue)]
    b += "traceId" -> JSONString(s.traceId)
    b += "spanId"  -> JSONString(s.spanId)
    s.context.traceState.foreach(t => b += "traceState" -> JSONString(t))
    s.parentSpanId.foreach(p => b += "parentSpanId" -> JSONString(p))
    b += "name"              -> JSONString(s.name)
    b += "kind"              -> JSONLong(kind(s.kind))
    b += "startTimeUnixNano" -> int64(s.startEpochNanos)
    b += "endTimeUnixNano"   -> int64(s.endEpochNanos)
    b += "attributes"        -> attributes(s.attributes)
    if s.events.nonEmpty then
      b += "events" ->
        JSONArray(
          s.events
            .map { e =>
              JSONObject(
                Seq(
                  "timeUnixNano" -> int64(e.epochNanos),
                  "name"         -> JSONString(e.name),
                  "attributes"   -> attributes(e.attributes)
                )
              )
            }
            .toIndexedSeq
        )
    val status = Seq.newBuilder[(String, JSONValue)]
    status += "code" -> JSONLong(statusCode(s.status))
    s.statusMessage.foreach(m => status += "message" -> JSONString(m))
    b += "status" -> JSONObject(status.result())
    JSONObject(b.result())

  end span

  private def histogram(instrument: HistogramInstrument, points: Seq[HistogramData]): JSONObject =
    JSONObject(
      Seq(
        "name"        -> JSONString(instrument.name),
        "unit"        -> JSONString(instrument.unit),
        "description" -> JSONString(instrument.description),
        "histogram"   ->
          JSONObject(
            Seq(
              "aggregationTemporality" -> JSONLong(Delta),
              "dataPoints"             ->
                JSONArray(
                  points
                    .map { p =>
                      JSONObject(
                        Seq(
                          "attributes"        -> attributes(p.attributes),
                          "startTimeUnixNano" -> int64(p.startEpochNanos),
                          "timeUnixNano"      -> int64(p.endEpochNanos),
                          "count"             -> int64(p.count),
                          "sum"               -> JSONDouble(p.sum),
                          "min"               -> JSONDouble(p.min),
                          "max"               -> JSONDouble(p.max),
                          "bucketCounts"      -> JSONArray(p.bucketCounts.map(int64)),
                          "explicitBounds"    -> JSONArray(instrument.boundaries.map(JSONDouble(_)))
                        )
                      )
                    }
                    .toIndexedSeq
                )
            )
          )
      )
    )

  private def attributes(attrs: Map[String, AttributeValue]): JSONArray = JSONArray(
    attrs
      .map { (k, v) =>
        JSONObject(Seq("key" -> JSONString(k), "value" -> anyValue(v)))
      }
      .toIndexedSeq
  )

  private def anyValue(v: AttributeValue): JSONObject =
    v match
      case s: String =>
        JSONObject(Seq("stringValue" -> JSONString(s)))
      case l: Long =>
        JSONObject(Seq("intValue" -> int64(l)))
      case d: Double =>
        JSONObject(Seq("doubleValue" -> JSONDouble(d)))
      case b: Boolean =>
        JSONObject(Seq("boolValue" -> JSONBoolean(b)))

  private def int64(v: Long): JSONString = JSONString(v.toString)

  private def kind(k: SpanKind): Long =
    k match
      case SpanKind.Internal =>
        1
      case SpanKind.Server =>
        2
      case SpanKind.Client =>
        3

  private def statusCode(s: SpanStatus): Long =
    s match
      case SpanStatus.Unset =>
        0
      case SpanStatus.Ok =>
        1
      case SpanStatus.Error =>
        2

end OtlpJson
