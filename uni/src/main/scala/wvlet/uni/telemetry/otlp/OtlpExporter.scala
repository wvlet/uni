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
import wvlet.uni.rx.{Cancelable, Rx, RxRunner, RxTimeProvider}
import wvlet.uni.telemetry.*

import scala.collection.mutable
import scala.util.{Failure, Success}

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
  * Spans and events (as log records, to `/v1/logs`) are buffered and histograms aggregated in
  * memory, then sent every `flushIntervalMillis` with Uni's async HTTP client. Histograms use delta
  * temporality. A failed send is logged once and dropped: telemetry never blocks or fails the code
  * it observes. [[shutdown]] stops the timer and sends what is left.
  */
class OtlpExporter(config: OtlpConfig = OtlpConfig())
    extends SpanExporter
    with MetricExporter
    with EventExporter
    with LogSupport:
  private val endpoint   = config.endpoint.stripSuffix("/")
  private val spans      = mutable.ArrayBuffer.empty[SpanData]
  private val events     = mutable.ArrayBuffer.empty[EventData]
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
      .schedulePeriodically(config.flushIntervalMillis, config.flushIntervalMillis) {
        RxRunner.run(flush())(_ => ())
      }

  override def exportSpan(span: SpanData): Unit = synchronized {
    if closed then
      ()
    else if spans.size >= config.maxQueueSize then
      dropped += 1
    else
      spans += span
  }

  override def exportEvent(event: EventData): Unit = synchronized {
    if closed then
      ()
    else if events.size >= config.maxQueueSize then
      dropped += 1
    else
      events += event
  }

  override def recordHistogram(
      resource: Map[String, AttributeValue],
      instrument: HistogramInstrument,
      value: Double,
      attributes: Map[String, AttributeValue]
  ): Unit =
    if !closed then
      histograms.record(resource, instrument, value, attributes)

  /**
    * Send the buffered spans and the metrics collected since the last flush. The data is taken when
    * called; the returned Rx sends it, one request after another, and emits once when done. A
    * failed request is logged, not raised.
    */
  def flush(): Rx[Unit] =
    val (batch, eventBatch, droppedNow) = synchronized {
      val b = spans.toSeq
      spans.clear()
      val e = events.toSeq
      events.clear()
      val d = dropped
      dropped = 0
      (b, e, d)
    }
    if droppedNow > 0 then
      warn(s"Dropped ${droppedNow} spans or events: more than ${config.maxQueueSize} were buffered")
    val metrics = histograms.collect()
    val bodies  =
      batch.grouped(config.maxBatchSize).map(b => "/v1/traces" -> OtlpJson.traces(b)).toSeq ++
        Option.when(metrics.nonEmpty)("/v1/metrics" -> OtlpJson.metrics(metrics)) ++
        eventBatch.grouped(config.maxBatchSize).map(b => "/v1/logs" -> OtlpJson.logs(b))
    bodies.foldLeft(Rx.single(())) { case (acc, (path, body)) =>
      acc.flatMap(_ => post(path, body))
    }

  private def post(path: String, body: JSONObject): Rx[Unit] =
    val request =
      config
        .headers
        .foldLeft(Request.post(s"${endpoint}${path}").withJsonContent(body.toJSON)) {
          case (r, (k, v)) =>
            r.addHeader(k, v)
        }
    client
      .send(request)
      .transformTry {
        case Success(_) =>
          if failing then
            failing = false
            info(s"Sending telemetry to ${endpoint} again")
          Success(())
        case Failure(e) =>
          if !failing then
            failing = true
            warn(s"Failed to send telemetry to ${endpoint}${path}: ${e.getMessage}")
          Success(())
      }

  /**
    * Stop the flush timer and send what is buffered. The returned Rx emits once the last request is
    * done; on the JVM and Scala Native, `shutdown().await` waits for it before the process exits.
    */
  override def shutdown(): Rx[Unit] =
    val first = synchronized {
      val f = !closed
      closed = true
      f
    }
    if first then
      timer.cancel
      flush()
    else
      Rx.single(())

  /** Start [[shutdown]] without waiting for the last request. */
  override def close(): Unit = RxRunner.run(shutdown())(_ => ())

end OtlpExporter
