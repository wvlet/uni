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

import wvlet.uni.http.*
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.rx.{Rx, RxRunner, RxTimeProvider}
import wvlet.uni.telemetry.*
import wvlet.uni.test.UniTest

class OtlpExporterTest extends UniTest:

  /** Records the requests an async client sends and answers each with `status`. */
  private class RecordingAsyncChannel(status: HttpStatus = HttpStatus.Ok_200)
      extends HttpAsyncChannel:
    var requests: Seq[Request]                                                  = Seq.empty
    override def send(request: Request, config: HttpClientConfig): Rx[Response] =
      synchronized(requests :+= request)
      Rx.single(Response(status))

    override def sendStreaming(request: Request, config: HttpClientConfig): Rx[Array[Byte]] =
      Rx.empty

    override def close(): Unit = ()

  private def factory(channel: HttpAsyncChannel): HttpChannelFactory =
    new HttpChannelFactory:
      override def newChannel: HttpChannel           = throw NotImplementedError("async only")
      override def newAsyncChannel: HttpAsyncChannel = channel

  private def obj(v: JSONValue): JSONObject                = v.asInstanceOf[JSONObject]
  private def arr(v: JSONValue): IndexedSeq[JSONValue]     = v.asInstanceOf[JSONArray].v
  private def field(v: JSONValue, name: String): JSONValue = obj(v).get(name).get
  private def str(v: JSONValue): String                    = v.asInstanceOf[JSONString].v

  private def attr(attrs: JSONValue, key: String): JSONValue =
    val a = arr(attrs).find(a => str(field(a, "key")) == key).get
    field(a, "value")

  private val span = SpanData(
    TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", sampled = true),
    Some("1111111111111111"),
    "OrderApi/get",
    SpanKind.Server,
    1000L,
    3000L,
    Map("http.response.status_code" -> 200L, "rpc.method" -> "get", "ratio" -> 0.5, "ok" -> true),
    Seq(SpanEvent("exception", 2000L, Map("exception.type" -> "E"))),
    SpanStatus.Error,
    Some("boom"),
    Map("service.name" -> "orders")
  )

  test("encode spans as OTLP JSON") {
    val json          = JSON.parse(OtlpJson.traces(Seq(span)).toJSON)
    val resourceSpans = arr(field(json, "resourceSpans")).head
    str(
      field(
        attr(field(field(resourceSpans, "resource"), "attributes"), "service.name"),
        "stringValue"
      )
    ) shouldBe "orders"
    val scopeSpans = arr(field(resourceSpans, "scopeSpans")).head
    str(field(field(scopeSpans, "scope"), "name")) shouldBe "wvlet.uni"
    val s = arr(field(scopeSpans, "spans")).head
    str(field(s, "traceId")) shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
    str(field(s, "spanId")) shouldBe "00f067aa0ba902b7"
    str(field(s, "parentSpanId")) shouldBe "1111111111111111"
    field(s, "kind") shouldBe JSONLong(2)
    str(field(s, "startTimeUnixNano")) shouldBe "1000"
    str(field(s, "endTimeUnixNano")) shouldBe "3000"
    val attrs = field(s, "attributes")
    str(field(attr(attrs, "http.response.status_code"), "intValue")) shouldBe "200"
    str(field(attr(attrs, "rpc.method"), "stringValue")) shouldBe "get"
    field(attr(attrs, "ratio"), "doubleValue") shouldBe JSONDouble(0.5)
    field(attr(attrs, "ok"), "boolValue") shouldBe JSONBoolean(true)
    str(field(arr(field(s, "events")).head, "name")) shouldBe "exception"
    field(field(s, "status"), "code") shouldBe JSONLong(2)
    str(field(field(s, "status"), "message")) shouldBe "boom"
  }

  test("aggregate histograms into upper-inclusive buckets, reset on collect") {
    val instrument = HistogramInstrument("latency", "s", "test", IndexedSeq(1.0, 2.0))
    val agg        = HistogramAggregator()
    Seq(0.5, 1.0, 1.5, 3.0).foreach(v => agg.record(Map.empty, instrument, v, Map("k" -> "a")))
    agg.record(Map.empty, instrument, 2.0, Map("k" -> "b"))
    agg.record(Map.empty, instrument, Double.NaN, Map("k" -> "a"))
    val Seq(a, b) = agg.collect()
    a.attributes shouldBe Map("k" -> "a")
    a.count shouldBe 4L
    a.sum shouldBe 6.0
    a.min shouldBe 0.5
    a.max shouldBe 3.0
    a.bucketCounts shouldBe IndexedSeq(2L, 1L, 1L)
    b.bucketCounts shouldBe IndexedSeq(0L, 1L, 0L)
    agg.collect() shouldBe Seq.empty
  }

  test("encode histograms with delta temporality") {
    val agg = HistogramAggregator()
    agg.record(
      Map("service.name" -> "orders"),
      HistogramInstrument.HttpServerRequestDuration,
      0.02,
      Map("http.route" -> "/x")
    )
    val json   = JSON.parse(OtlpJson.metrics(agg.collect()).toJSON)
    val rm     = arr(field(json, "resourceMetrics")).head
    val metric = arr(field(arr(field(rm, "scopeMetrics")).head, "metrics")).head
    str(field(metric, "name")) shouldBe "http.server.request.duration"
    str(field(metric, "unit")) shouldBe "s"
    val h = field(metric, "histogram")
    field(h, "aggregationTemporality") shouldBe JSONLong(1)
    val p = arr(field(h, "dataPoints")).head
    str(field(p, "count")) shouldBe "1"
    arr(field(p, "bucketCounts")).size shouldBe
      HistogramInstrument.DurationSecondsBoundaries.size + 1
    str(field(attr(field(p, "attributes"), "http.route"), "stringValue")) shouldBe "/x"
  }

  test("send buffered spans and metrics on each flush interval") {
    val channel = RecordingAsyncChannel()
    val time    = RxTimeProvider.manual()
    val otlp    = OtlpExporter(
      OtlpConfig()
        .copy(endpoint = "http://collector:4318/")
        .withHeader("DD-API-KEY", "test-key")
        .withFlushIntervalMillis(1000)
        .withTimeProvider(time)
        .withClient(Http.client.withChannelFactory(factory(channel)))
    )
    val telemetry = Telemetry()
      .withServiceName("orders")
      .withExporter(otlp)
      .withMetricExporter(otlp)
    telemetry.trace("work")(_ => ())
    telemetry.recordHistogram(HistogramInstrument.HttpServerRequestDuration, 0.1)
    channel.requests shouldBe Seq.empty

    time.advance(1000)
    channel.requests.map(_.uri) shouldBe
      Seq("http://collector:4318/v1/traces", "http://collector:4318/v1/metrics")
    channel.requests.head.header("DD-API-KEY") shouldBe Some("test-key")
    channel.requests.head.contentType.map(_.value) shouldBe Some("application/json")
    val body = JSON.parse(channel.requests.head.content.asString.get)
    val rs   = arr(field(body, "resourceSpans")).head
    str(
      field(attr(field(field(rs, "resource"), "attributes"), "service.name"), "stringValue")
    ) shouldBe "orders"

    // Nothing new: nothing is sent
    time.advance(1000)
    channel.requests.size shouldBe 2

    telemetry.trace("last")(_ => ())
    telemetry.close()
    channel.requests.size shouldBe 3
    // A closed exporter stops its timer and takes no more spans
    telemetry.trace("after")(_ => ())
    time.advance(5000)
    channel.requests.size shouldBe 3
  }

  test("split large span batches and drop spans past the queue limit") {
    val channel = RecordingAsyncChannel()
    val time    = RxTimeProvider.manual()
    val otlp    = OtlpExporter(
      OtlpConfig()
        .withMaxBatchSize(2)
        .withMaxQueueSize(5)
        .withTimeProvider(time)
        .withClient(Http.client.withChannelFactory(factory(channel)))
    )
    val telemetry = Telemetry().withExporter(otlp)
    val logs      = captureLogs {
      (1 to 7).foreach(i => telemetry.trace(s"s${i}")(_ => ()))
      RxRunner.run(otlp.flush())(_ => ())
    }
    channel.requests.size shouldBe 3
    logs.warnings shouldContain "Dropped 2 spans"
    otlp.close()
  }

  test("log a failing endpoint once and keep the service running") {
    val channel = RecordingAsyncChannel(HttpStatus.BadRequest_400)
    val otlp    = OtlpExporter(
      OtlpConfig()
        .withTimeProvider(RxTimeProvider.manual())
        .withClient(Http.client.withChannelFactory(factory(channel)).noRetry)
    )
    val telemetry = Telemetry().withExporter(otlp)
    val logs      = captureLogs {
      telemetry.trace("a")(_ => ())
      RxRunner.run(otlp.flush())(_ => ())
      telemetry.trace("b")(_ => ())
      RxRunner.run(otlp.flush())(_ => ())
    }
    logs.warnings.split("\n").count(_.contains("Failed to send telemetry")) shouldBe 1
    otlp.close()
  }

  test("send events as OTLP logs") {
    val channel = RecordingAsyncChannel()
    val otlp    = OtlpExporter(
      OtlpConfig()
        .withTimeProvider(RxTimeProvider.manual())
        .withClient(Http.client.withChannelFactory(factory(channel)))
    )
    val telemetry = Telemetry().withEventExporter(otlp)
    telemetry.emitEvent("ui.press", Map("ui.ref" -> "button:save"))
    RxRunner.run(otlp.flush())(_ => ())
    channel.requests.map(_.uri) shouldBe Seq("http://localhost:4318/v1/logs")
    channel.requests.head.content.asString.get.contains("\"eventName\":\"ui.press\"") shouldBe true
    otlp.close()
  }

end OtlpExporterTest
