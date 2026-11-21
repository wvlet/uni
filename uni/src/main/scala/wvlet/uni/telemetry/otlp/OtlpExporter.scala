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

import wvlet.uni.http.{Http, HttpAsyncClient, HttpClientConfig, Request}
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{Cancelable, OnCompletion, OnNext, RxRunner, RxTimeProvider}
import wvlet.uni.telemetry.*
import wvlet.uni.util.Result

import scala.collection.mutable

/**
  * Where and how often [[OtlpExporter]] sends.
  *
  * @param endpoint
  *   the OTLP/HTTP base URL; spans go to `/v1/traces` and metrics to `/v1/metrics`. The Datadog
  *   Agent's OTLP receiver and the OpenTelemetry Collector listen on port 4318
  * @param headers
  *   added to every request, e.g. an API key for a hosted OTLP intake
  * @param flushIntervalMillis
  *   how often buffered spans are sent and metrics are collected
  * @param maxBatchSize
  *   the most spans in one request
  * @param maxQueueSize
  *   the most spans buffered between flushes; more are dropped and counted
  */
case class OtlpConfig(
    endpoint: String = "http://localhost:4318",
    headers: Map[String, String] = Map.empty,
    flushIntervalMillis: Long = 5000,
    maxBatchSize: Int = 512,
    maxQueueSize: Int = 4096,
    client: HttpClientConfig = Http.client,
    timeProvider: RxTimeProvider = RxTimeProvider.system,
    clock: TelemetryClock = TelemetryClock.system
):
  require(flushIntervalMillis > 0, s"flushIntervalMillis must be positive: ${flushIntervalMillis}")
  require(maxBatchSize > 0, s"maxBatchSize must be positive: ${maxBatchSize}")

  def withEndpoint(url: String): OtlpConfig               = copy(endpoint = url.stripSuffix("/"))
  def withHeader(name: String, value: String): OtlpConfig = copy(headers =
    headers.updated(name, value)
  )

  def noHeaders: OtlpConfig                             = copy(headers = Map.empty)
  def withFlushIntervalMillis(millis: Long): OtlpConfig = copy(flushIntervalMillis = millis)
  def withMaxBatchSize(n: Int): OtlpConfig              = copy(maxBatchSize = n)
  def withMaxQueueSize(n: Int): OtlpConfig              = copy(maxQueueSize = n)
  def withClient(config: HttpClientConfig): OtlpConfig  = copy(client = config)
  def withTimeProvider(p: RxTimeProvider): OtlpConfig   = copy(timeProvider = p)
  def withClock(c: TelemetryClock): OtlpConfig          = copy(clock = c)

end OtlpConfig

/**
  * Sends spans and metrics to an OTLP/HTTP endpoint as JSON, on every platform: the JVM, Scala
  * Native, Node.js, and browsers (where the endpoint must allow the page's origin).
  *
  * Spans are buffered and histograms aggregated in memory, then sent every `flushIntervalMillis`
  * with Uni's async HTTP client. Histograms use delta temporality. A failed send is logged once and
  * dropped: telemetry never blocks or fails the code it observes. [[close]] stops the timer and
  * sends what is left.
  */
class OtlpExporter(config: OtlpConfig) extends SpanExporter with MetricExporter with LogSupport:
  private val spans      = mutable.ArrayBuffer.empty[SpanData]
  private var dropped    = 0L
  private val histograms = HistogramAggregator(config.clock)
  @volatile
  private var closed = false

  @volatile
  private var failing = false

  // The exporter's own calls must not be traced, or each flush would produce spans to flush
  private lazy val client: HttpAsyncClient = config.client.noTelemetry.newAsyncClient

  private val timer: Cancelable =
    config
      .timeProvider
      .schedulePeriodically(config.flushIntervalMillis, config.flushIntervalMillis)(flush())

  override def exportSpan(span: SpanData): Unit = synchronized {
    if closed then
      ()
    else if spans.size >= config.maxQueueSize then
      dropped += 1
    else
      spans += span
  }

  override def recordHistogram(
      resource: Map[String, AttributeValue],
      instrument: HistogramInstrument,
      value: Double,
      attributes: Map[String, AttributeValue]
  ): Unit =
    if !closed then
      histograms.record(resource, instrument, value, attributes)

  /** Send the buffered spans and the metrics collected since the last flush. */
  def flush(): Unit =
    val (batch, droppedNow) = synchronized {
      val b = spans.toSeq
      spans.clear()
      val d = dropped
      dropped = 0
      (b, d)
    }
    if droppedNow > 0 then
      warn(s"Dropped ${droppedNow} spans: more than ${config.maxQueueSize} were buffered")
    batch.grouped(config.maxBatchSize).foreach(b => post("/v1/traces", OtlpJson.traces(b)))
    val metrics = histograms.collect()
    if metrics.nonEmpty then
      post("/v1/metrics", OtlpJson.metrics(metrics))

  private def post(path: String, body: JSONObject): Unit =
    val request =
      config
        .headers
        .foldLeft(Request.post(s"${config.endpoint}${path}").withJsonContent(body.toJSON)) {
          case (r, (k, v)) =>
            r.addHeader(k, v)
        }
    // Recovery is judged by a delivered response: a failed stream can also report completion
    RxRunner.run(client.send(request)) {
      case OnNext(_) =>
        if failing then
          failing = false
          info(s"Sending telemetry to ${config.endpoint} again")
      case OnCompletion(Result.Failure(e)) =>
        if !failing then
          failing = true
          warn(s"Failed to send telemetry to ${config.endpoint}${path}: ${e.getMessage}")
      case _ =>
    }

  /** Stop the flush timer and send what is buffered. */
  override def close(): Unit =
    if !closed then
      timer.cancel
      flush()
      closed = true

end OtlpExporter

object OtlpExporter:
  def apply(config: OtlpConfig = OtlpConfig()): OtlpExporter = new OtlpExporter(config)
