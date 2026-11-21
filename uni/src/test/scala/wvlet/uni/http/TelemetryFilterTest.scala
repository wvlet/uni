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

import wvlet.uni.util.Result
import wvlet.uni.http.router.{Endpoint, Router, RouterHandler}
import wvlet.uni.http.rpc.{RPCDispatcher, RPCRouter}
import wvlet.uni.rx.{OnCompletion, OnNext, Rx, RxRunner}
import wvlet.uni.telemetry.{
  MetricExporter,
  SpanExporter,
  SpanKind,
  SpanStatus,
  Telemetry,
  TraceContext
}
import wvlet.uni.test.UniTest

object TelemetryFilterTest:
  trait OrderApi:
    def get(id: Int): String
    def fail(id: Int): String
    def currentTrace(): String

  class OrderApiImpl extends OrderApi:
    def get(id: Int): String   = s"order${id}"
    def fail(id: Int): String  = throw IllegalStateException("boom")
    def currentTrace(): String = Telemetry.currentContext.map(_.traceId).getOrElse("none")

  class OrderController:
    @Endpoint(HttpMethod.GET, "/orders/:id")
    def order(id: Int): String = s"order${id}"

  /** Routes each request to a handler in memory, recording the requests it sends. */
  class RecordingChannel(responses: Request => Response) extends HttpChannel:
    var requests: Seq[Request] = Seq.empty

    override def send(request: Request, config: HttpClientConfig): Response =
      requests :+= request
      responses(request)

    override def close(): Unit = ()

  class RecordingChannelFactory(channel: RecordingChannel) extends HttpChannelFactory:
    override def newChannel: HttpChannel = channel
    override def newAsyncChannel         = throw NotImplementedError("sync only")

end TelemetryFilterTest

class TelemetryFilterTest extends UniTest:
  import TelemetryFilterTest.*

  private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val spanId  = "00f067aa0ba902b7"

  private val rpcRouter  = RPCRouter.of[OrderApi](OrderApiImpl())
  private val dispatcher = RPCDispatcher(rpcRouter)
  private val service    = rpcRouter.serviceName
  private val handler    = RxHttpHandler(dispatcher.dispatch)

  private def run(rx: Rx[Response]): Option[Response] =
    var result: Option[Response] = None
    RxRunner.runOnce(rx) {
      case OnNext(r: Response) =>
        result = Some(r)
      case _ =>
    }
    result

  private def rpc(method: String, json: String, headers: (String, String)*): Request =
    headers.foldLeft(
      Request.post(s"/${service}/${method}").withJsonContent(s"""{"request":${json}}""")
    ) { case (r, (k, v)) =>
      r.addHeader(k, v)
    }

  test("trace an RPC call as a server span named by the method") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    val resp     = run(
      filter(rpc("get", """{"id":1}""").addHeader(HttpHeader.Host, "api:8080"), handler)
    )
    resp.map(_.status) shouldBe Some(HttpStatus.Ok_200)
    val span = exporter.spans.head
    span.name shouldBe s"${service}/get"
    span.kind shouldBe SpanKind.Server
    span.parentSpanId shouldBe None
    span.status shouldBe SpanStatus.Unset
    span.attributes("rpc.system") shouldBe "uni"
    span.attributes("rpc.service") shouldBe service
    span.attributes("rpc.method") shouldBe "get"
    span.attributes("http.request.method") shouldBe "POST"
    span.attributes("http.response.status_code") shouldBe 200L
    span.attributes("server.address") shouldBe "api"
    span.attributes("server.port") shouldBe 8080L
  }

  test("continue the caller's trace from traceparent") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    run(filter(rpc("get", """{"id":1}""", "traceparent" -> s"00-${traceId}-${spanId}-01"), handler))
    val span = exporter.spans.head
    span.traceId shouldBe traceId
    span.parentSpanId shouldBe Some(spanId)
  }

  test("don't record a trace the caller didn't sample") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    run(filter(rpc("get", """{"id":1}""", "traceparent" -> s"00-${traceId}-${spanId}-00"), handler))
    exporter.spans shouldBe Seq.empty
  }

  test("make the server span current while the RPC method runs") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    val resp     = run(
      filter(rpc("currentTrace", "{}", "traceparent" -> s"00-${traceId}-${spanId}-01"), handler)
    )
    resp.flatMap(_.contentAsString) shouldBe Some(s"\"${traceId}\"")
    Telemetry.currentSpan shouldBe None
  }

  test("mark a server span failed on a 5xx response") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    captureLogs {
      run(filter(rpc("fail", """{"id":1}"""), handler))
    }
    val span = exporter.spans.head
    span.name shouldBe s"${service}/fail"
    span.status shouldBe SpanStatus.Error
    span.attributes("http.response.status_code") shouldBe 500L
    span.attributes("error.type") shouldBe "500"
  }

  test("record a handler exception") {
    val exporter                   = SpanExporter.inMemory()
    val filter                     = TelemetryFilter(Telemetry().withExporter(exporter))
    var failure: Option[Throwable] = None
    RxRunner.runOnce(filter(Request.get("/x"), _ => throw IllegalStateException("crash"))) {
      case OnCompletion(Result.Failure(e)) =>
        failure = Some(e)
      case _ =>
    }
    failure.map(_.getMessage) shouldBe Some("crash")
    val span = exporter.spans.head
    span.name shouldBe "GET"
    span.status shouldBe SpanStatus.Error
    span.events.map(_.name) shouldBe Seq("exception")
  }

  test("name a router span by the route template") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    run(filter(Request.get("/orders/42"), RouterHandler(Router.of[OrderController])))
    val span = exporter.spans.head
    span.name shouldBe "GET /orders/:id"
    span.attributes("http.route") shouldBe "/orders/:id"
    span.attributes("url.path") shouldBe "/orders/42"
  }

  test("end a streaming span when the stream ends") {
    val exporter = SpanExporter.inMemory()
    val filter   = TelemetryFilter(Telemetry().withExporter(exporter))
    val stream   = RxHttpHandler { _ =>
      Rx.single(
        Response.eventStream(Rx.fromSeq(Seq(ServerSentEvent.data("a"), ServerSentEvent.data("b"))))
      )
    }
    val resp = run(filter(Request.get("/events"), stream)).get
    exporter.spans shouldBe Seq.empty
    var events = 0
    RxRunner.run(resp.events) {
      case OnNext(_) =>
        events += 1
      case _ =>
    }
    events shouldBe 2
    exporter.spans.size shouldBe 1
  }

  test("pass requests through when telemetry is disabled") {
    val filter                = TelemetryFilter(Telemetry.noop)
    var seen: Option[Request] = None
    run(
      filter(
        Request.get("/x"),
        req =>
          seen = Some(req)
          Rx.single(Response.ok)
      )
    )
    seen.flatMap(_.attribute(TelemetryFilter.CurrentSpan)) shouldBe None
  }

  test("trace a client call as a child of the current span and send traceparent") {
    val exporter  = SpanExporter.inMemory()
    val telemetry = Telemetry().withExporter(exporter)
    val channel   = RecordingChannel(_ => Response.ok)
    val client    =
      Http
        .client
        .withChannelFactory(RecordingChannelFactory(channel))
        .withTelemetry(telemetry)
        .newSyncClient
    telemetry.trace("parent") { _ =>
      client.send(
        Request
          .get("https://user:pw@example.com:8443/items?token=secret")
          .addHeader("tracestate", "other=trace")
      )
    }
    val Seq(clientSpan, parent) = exporter.spans
    clientSpan.kind shouldBe SpanKind.Client
    clientSpan.name shouldBe "GET"
    clientSpan.parentSpanId shouldBe Some(parent.spanId)
    clientSpan.attributes("url.full") shouldBe "https://example.com:8443/items"
    clientSpan.attributes("server.address") shouldBe "example.com"
    clientSpan.attributes("server.port") shouldBe 8443L
    clientSpan.attributes("http.response.status_code") shouldBe 200L
    val sent = channel.requests.head.header("traceparent").flatMap(TraceContext.parse(_))
    channel.requests.head.header("tracestate") shouldBe None
    sent.map(_.traceId) shouldBe Some(parent.traceId)
    sent.map(_.spanId) shouldBe Some(clientSpan.spanId)
  }

  test("record retries as events of one client span") {
    val exporter = SpanExporter.inMemory()
    var calls    = 0
    val channel  = RecordingChannel { _ =>
      calls += 1
      if calls < 3 then
        Response(HttpStatus.ServiceUnavailable_503)
      else
        Response.ok
    }
    val client =
      Http
        .client
        .withChannelFactory(RecordingChannelFactory(channel))
        .withTelemetry(Telemetry().withExporter(exporter))
        .withRetryContext(
          wvlet
            .uni
            .control
            .Retry
            .withBackOff(maxRetry = 3, initialIntervalMillis = 1)
            .noRetryLogging
        )
        .newSyncClient
    client.send(Request.get("http://localhost/x"))
    val span = exporter.spans.head
    span.events.map(_.name) shouldBe Seq("http.request.resend", "http.request.resend")
    span.attributes("http.request.resend_count") shouldBe 2L
    span.status shouldBe SpanStatus.Unset
  }

  test("mark a client span failed on a 4xx response") {
    val exporter = SpanExporter.inMemory()
    val client   =
      Http
        .client
        .withChannelFactory(
          RecordingChannelFactory(RecordingChannel(_ => Response(HttpStatus.NotFound_404)))
        )
        .withTelemetry(Telemetry().withExporter(exporter))
        .noRetry
        .newSyncClient
    intercept[HttpException] {
      client.send(Request.get("http://localhost/missing"))
    }
    val span = exporter.spans.head
    span.status shouldBe SpanStatus.Error
    span.attributes("http.response.status_code") shouldBe 404L
    span.attributes("error.type") shouldBe "404"
  }

  test("send no traceparent from a client without telemetry") {
    val channel = RecordingChannel(_ => Response.ok)
    val client  = Http.client.withChannelFactory(RecordingChannelFactory(channel)).newSyncClient
    client.send(Request.get("http://localhost/x"))
    channel.requests.head.header("traceparent") shouldBe None
  }

  test("trace an async client call when the Rx runs") {
    val exporter           = SpanExporter.inMemory()
    val telemetry          = Telemetry().withExporter(exporter)
    var sent: Seq[Request] = Seq.empty
    val channel            =
      new HttpAsyncChannel:
        override def send(request: Request, config: HttpClientConfig): Rx[Response] =
          sent :+= request
          Rx.single(Response.ok)
        override def sendStreaming(request: Request, config: HttpClientConfig): Rx[Array[Byte]] =
          Rx.empty
        override def close(): Unit = ()
    val factory =
      new HttpChannelFactory:
        override def newChannel: HttpChannel           = throw NotImplementedError("async only")
        override def newAsyncChannel: HttpAsyncChannel = channel
    val client = Http.client.withChannelFactory(factory).withTelemetry(telemetry).newAsyncClient
    val parent = telemetry.startSpan("parent")
    val rx     = Telemetry.withSpan(parent)(client.send(Request.get("http://localhost/a")))
    exporter.spans shouldBe Seq.empty
    RxRunner.runOnce(rx)(_ => ())
    parent.end()
    val Seq(clientSpan, parentSpan) = exporter.spans
    clientSpan.parentSpanId shouldBe Some(parentSpan.spanId)
    clientSpan.attributes("http.response.status_code") shouldBe 200L
    sent.head.header("traceparent").flatMap(TraceContext.parse(_)).map(_.spanId) shouldBe
      Some(clientSpan.spanId)
  }

  test("record request durations with metrics only, without traces") {
    val metrics   = MetricExporter.inMemory()
    val telemetry = Telemetry().withMetricExporter(metrics)
    val filter    = TelemetryFilter(telemetry)
    val channel   = RecordingChannel(_ => Response.ok)
    val client    =
      Http
        .client
        .withChannelFactory(RecordingChannelFactory(channel))
        .withTelemetry(telemetry)
        .newSyncClient
    val routes = RouterHandler(Router.of[OrderController])
    run(
      filter(
        Request.get("/orders/1"),
        req =>
          client.send(Request.get("http://inventory:9000/stock"))
          routes.handle(req)
      )
    )
    run(filter(rpc("get", """{"id":1}"""), handler))
    // Metrics only: no trace to propagate
    channel.requests.head.header("traceparent") shouldBe None
    val points = metrics.collect()
    val byName = points.groupBy(_.instrument.name)
    val server = byName("http.server.request.duration")
    server.map(_.attributes.get("http.route")) shouldContain Some("/orders/:id")
    server.flatMap(_.attributes.get("rpc.method")) shouldBe Seq("get")
    server.forall(_.attributes("http.response.status_code") == 200L) shouldBe true
    val clientPoint = byName("http.client.request.duration").head
    clientPoint.attributes shouldBe
      Map(
        "http.request.method"       -> "GET",
        "server.address"            -> "inventory",
        "server.port"               -> 9000L,
        "http.response.status_code" -> 200L
      )
    clientPoint.count shouldBe 1L
  }

end TelemetryFilterTest
