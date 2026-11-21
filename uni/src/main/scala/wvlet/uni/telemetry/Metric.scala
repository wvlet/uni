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

import scala.collection.mutable

/**
  * A histogram metric, as in OpenTelemetry: values are counted into buckets split at `boundaries`
  * (ascending), with their count, sum, min, and max.
  */
case class HistogramInstrument(
    name: String,
    unit: String,
    description: String,
    boundaries: IndexedSeq[Double] = HistogramInstrument.DurationSecondsBoundaries
)

object HistogramInstrument:
  /** The OpenTelemetry advice for HTTP durations in seconds. */
  val DurationSecondsBoundaries: IndexedSeq[Double] = IndexedSeq(
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0
  )

  val HttpServerRequestDuration: HistogramInstrument = HistogramInstrument(
    "http.server.request.duration",
    "s",
    "Duration of HTTP server requests"
  )

  val HttpClientRequestDuration: HistogramInstrument = HistogramInstrument(
    "http.client.request.duration",
    "s",
    "Duration of HTTP client requests"
  )

  /** The OpenTelemetry GenAI advice for inference durations in seconds. */
  val GenAiDurationSecondsBoundaries: IndexedSeq[Double] = IndexedSeq(
    0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92
  )

  /** The OpenTelemetry GenAI advice for token counts. */
  val TokenCountBoundaries: IndexedSeq[Double] = IndexedSeq(
    1.0, 4.0, 16.0, 64.0, 256.0, 1024.0, 4096.0, 16384.0, 65536.0, 262144.0, 1048576.0, 4194304.0,
    16777216.0, 67108864.0
  )

  /** The OpenTelemetry MCP advice for operation durations in seconds. */
  val McpDurationSecondsBoundaries: IndexedSeq[Double] = IndexedSeq(
    0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0
  )

  val GenAiClientInferenceDuration: HistogramInstrument = HistogramInstrument(
    "gen_ai.client.inference.duration",
    "s",
    "GenAI client inference operation duration",
    GenAiDurationSecondsBoundaries
  )

  val GenAiClientInferenceInputTokens: HistogramInstrument = HistogramInstrument(
    "gen_ai.client.inference.operation.input_tokens",
    "{token}",
    "Input tokens per GenAI client inference operation",
    TokenCountBoundaries
  )

  val GenAiClientInferenceOutputTokens: HistogramInstrument = HistogramInstrument(
    "gen_ai.client.inference.operation.output_tokens",
    "{token}",
    "Output tokens per GenAI client inference operation",
    TokenCountBoundaries
  )

  val McpClientOperationDuration: HistogramInstrument = HistogramInstrument(
    "mcp.client.operation.duration",
    "s",
    "Duration of MCP requests as seen by the client",
    McpDurationSecondsBoundaries
  )

  val McpServerOperationDuration: HistogramInstrument = HistogramInstrument(
    "mcp.server.operation.duration",
    "s",
    "Duration of MCP requests as seen by the server",
    McpDurationSecondsBoundaries
  )

end HistogramInstrument

/**
  * The values one attribute set of a histogram received during [[startEpochNanos]] to
  * [[endEpochNanos]].
  */
case class HistogramData(
    resource: Map[String, AttributeValue],
    instrument: HistogramInstrument,
    attributes: Map[String, AttributeValue],
    startEpochNanos: Long,
    endEpochNanos: Long,
    count: Long,
    sum: Double,
    min: Double,
    max: Double,
    bucketCounts: IndexedSeq[Long]
)

/**
  * Receives metric measurements. Implementations aggregate them and report on their own schedule;
  * `recordHistogram` runs on the thread that took the measurement, so it must be cheap.
  */
trait MetricExporter extends TelemetryExporter:
  def recordHistogram(
      resource: Map[String, AttributeValue],
      instrument: HistogramInstrument,
      value: Double,
      attributes: Map[String, AttributeValue]
  ): Unit

object MetricExporter:
  /** Drops every measurement. A [[Telemetry]] with this exporter records no metrics. */
  val none: MetricExporter = (_, _, _, _) => ()

  /** Aggregates measurements in memory, for tests. */
  def inMemory(clock: TelemetryClock = TelemetryClock.system): InMemoryMetricExporter =
    InMemoryMetricExporter(clock)

/** Keeps histograms in memory; `collect()` returns what was recorded since the last call. */
class InMemoryMetricExporter(clock: TelemetryClock) extends MetricExporter:
  private val aggregator = HistogramAggregator(clock)

  override def recordHistogram(
      resource: Map[String, AttributeValue],
      instrument: HistogramInstrument,
      value: Double,
      attributes: Map[String, AttributeValue]
  ): Unit = aggregator.record(resource, instrument, value, attributes)

  def collect(): Seq[HistogramData] = aggregator.collect()

/**
  * Aggregates histogram measurements per resource, instrument, and attribute set, with delta
  * temporality: [[collect]] returns the values since the previous call and starts over. Datadog and
  * other OTLP backends read delta histograms directly.
  */
class HistogramAggregator(clock: TelemetryClock = TelemetryClock.system):
  private class Point(boundaries: IndexedSeq[Double]):
    var count: Long = 0L
    var sum: Double = 0.0
    var min: Double = Double.PositiveInfinity
    var max: Double = Double.NegativeInfinity
    val buckets     = new Array[Long](boundaries.size + 1)

    def add(v: Double): Unit =
      count += 1
      sum += v
      if v < min then
        min = v
      if v > max then
        max = v
      // OpenTelemetry buckets are upper-inclusive: (b(i-1), b(i)]
      var i = 0
      while i < boundaries.size && v > boundaries(i) do
        i += 1
      buckets(i) += 1

  private type Key = (Map[String, AttributeValue], HistogramInstrument, Map[String, AttributeValue])

  private var points     = mutable.LinkedHashMap.empty[Key, Point]
  private var startNanos = clock.epochNanos

  def record(
      resource: Map[String, AttributeValue],
      instrument: HistogramInstrument,
      value: Double,
      attributes: Map[String, AttributeValue]
  ): Unit =
    if !value.isNaN && !value.isInfinite then
      synchronized {
        points
          .getOrElseUpdate((resource, instrument, attributes), Point(instrument.boundaries))
          .add(value)
      }

  def collect(): Seq[HistogramData] =
    val (collected, start) = synchronized {
      val c = points
      val s = startNanos
      points = mutable.LinkedHashMap.empty
      startNanos = clock.epochNanos
      (c, s)
    }
    val end = clock.epochNanos
    collected
      .map { case ((resource, instrument, attributes), p) =>
        HistogramData(
          resource,
          instrument,
          attributes,
          start,
          end,
          p.count,
          p.sum,
          p.min,
          p.max,
          p.buckets.toIndexedSeq
        )
      }
      .toSeq

end HistogramAggregator
