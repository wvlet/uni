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
package wvlet.uni.ai.mcp

import wvlet.uni.http.{Http, Request, RxHttpHandler}
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.{JSONLong, JSONObject, JSONString, JSONValue}
import wvlet.uni.rx.{Rx, RxResource}
import wvlet.uni.telemetry.{
  MetricExporter,
  SpanData,
  SpanExporter,
  SpanKind,
  SpanStatus,
  Telemetry,
  TraceContext
}
import wvlet.uni.test.UniTest

trait TraceService:
  def traceId(): String

class TraceServiceImpl extends TraceService:
  def traceId(): String = Telemetry.currentContext.map(_.traceId).getOrElse("none")

class MCPTelemetryTest extends UniTest:

  private class Traced:
    val spans     = SpanExporter.inMemory()
    val metrics   = MetricExporter.inMemory()
    val telemetry = Telemetry().withExporter(spans).withMetricExporter(metrics)

    def server: MCPServer = MCP
      .server
      .withTools[GreeterService](GreeterServiceImpl())
      .withTools[TraceService](TraceServiceImpl())
      .withTelemetry(telemetry)

    def mcpSpans: Seq[SpanData] = spans.spans.filter(_.attributes.contains("mcp.method.name"))

  private def message(id: Option[JSONValue], method: String, params: (String, JSONValue)*): String =
    JSONObject(
      Seq("jsonrpc" -> JSONString("2.0"), "method" -> JSONString(method)) ++ id.map("id" -> _) ++
        Option.when(params.nonEmpty)("params" -> JSONObject(params)).toSeq
    ).toJSON

  private def toolCall(tool: String, extra: (String, JSONValue)*): String = message(
    Some(JSONLong(1)),
    "tools/call",
    Seq("name" -> JSONString(tool), "arguments" -> JSONObject(Seq("name" -> JSONString("a")))) ++
      extra*
  )

  test("a tool call over stdio is a server span named after the method and tool") {
    val t = Traced()
    t.server
      .handleMessage(toolCall("hello"), Some("pipe"))
      .map { _ =>
        val span = t.mcpSpans.head
        span.name shouldBe "tools/call hello"
        span.kind shouldBe SpanKind.Server
        span.status shouldBe SpanStatus.Unset
        span.attributes("mcp.method.name") shouldBe "tools/call"
        span.attributes("gen_ai.operation.name") shouldBe "execute_tool"
        span.attributes("gen_ai.tool.name") shouldBe "hello"
        span.attributes("jsonrpc.request.id") shouldBe "1"
        span.attributes("network.transport") shouldBe "pipe"
        // Arguments and results are never recorded
        span.attributes.values.exists(_ == "a") shouldBe false
        val duration = t
          .metrics
          .collect()
          .filter(_.instrument.name == "mcp.server.operation.duration")
        duration.map(_.count) shouldBe Seq(1L)
        duration.head.attributes("gen_ai.tool.name") shouldBe "hello"
        duration.head.attributes.contains("jsonrpc.request.id") shouldBe false
      }
  }

  test("the server joins the trace in params._meta and makes the span current for the tool") {
    val t      = Traced()
    val caller = TraceContext.newRoot(sampled = true)
    val meta   = JSONObject(Seq("traceparent" -> JSONString(caller.toTraceParent)))
    t.server
      .handleMessage(toolCall("traceId", "_meta" -> meta))
      .map { line =>
        val span = t.mcpSpans.head
        span.traceId shouldBe caller.traceId
        span.parentSpanId shouldBe Some(caller.spanId)
        line.exists(_.contains(caller.traceId)) shouldBe true
      }
  }

  test("tool errors, JSON-RPC errors, and unknown methods") {
    val t      = Traced()
    val server = t.server
    for
      _ <- server.handleMessage(
        message(
          Some(JSONLong(1)),
          "tools/call",
          "name"      -> JSONString("explode"),
          "arguments" -> JSONObject(Seq("message" -> JSONString("boom")))
        )
      )
      _ <- server.handleMessage(toolCall("no_such_tool"))
      _ <- server.handleMessage(message(Some(JSONLong(2)), "custom/method"))
      _ <- server.handleMessage(message(None, "notifications/initialized"))
    yield
      val Seq(explode, unknownTool, unknownMethod, notification) = t.mcpSpans
      explode.attributes("error.type") shouldBe "tool_error"
      explode.status shouldBe SpanStatus.Error
      unknownTool.name shouldBe "tools/call"
      unknownTool.attributes.contains("gen_ai.tool.name") shouldBe false
      unknownTool.attributes("rpc.status_code") shouldBe "-32602"
      unknownTool.attributes("error.type") shouldBe "-32602"
      unknownMethod.name shouldBe "_OTHER"
      unknownMethod.attributes("rpc.status_code") shouldBe "-32601"
      notification.name shouldBe "notifications/initialized"
      notification.attributes.contains("jsonrpc.request.id") shouldBe false
  }

  test("a client span is the parent of the server span, through params._meta") {
    val t        = Traced()
    val server   = t.server
    val received = List.newBuilder[String]
    val handler  = RxHttpHandler { (request: Request) =>
      received += request.content.asString.getOrElse("")
      server.httpHandler.handle(request)
    }
    val config = MCP
      .client
      .withHttpClientConfig(Http.client.withChannelFactory(InMemoryChannelFactory(handler)))
      .withTelemetry(t.telemetry)
    RxResource
      .fromAutoCloseable(config.connect("http://test/mcp"))
      .use { client =>
        client
          .callTool("hello", JSONObject(Seq("name" -> JSONString("a"))))
          .map { result =>
            result.isError shouldBe false
            val spans  = t.mcpSpans.filter(_.name == "tools/call hello")
            val client = spans.find(_.kind == SpanKind.Client).get
            val srv    = spans.find(_.kind == SpanKind.Server).get
            srv.parentSpanId shouldBe Some(client.spanId)
            srv.traceId shouldBe client.traceId
            srv.attributes("network.transport") shouldBe "tcp"
            client.attributes("server.address") shouldBe "test"
            client.attributes("mcp.protocol.version") shouldBe MCPServer.LatestProtocolVersion
            // The HTTP client span nests under the MCP client span
            val http = t.spans.spans.find(_.attributes.contains("http.request.method")).get
            http.parentSpanId.isDefined shouldBe true
            t.mcpSpans.exists(s => http.parentSpanId.contains(s.spanId)) shouldBe true
            received.result().last.contains("\"_meta\"") shouldBe true
          }
      }
  }

  test("a client without telemetry leaves params unchanged") {
    val server   = MCP.server.withTools[GreeterService](GreeterServiceImpl())
    val received = List.newBuilder[String]
    val handler  = RxHttpHandler { (request: Request) =>
      received += request.content.asString.getOrElse("")
      server.httpHandler.handle(request)
    }
    RxResource
      .fromAutoCloseable(
        MCP
          .client
          .withHttpClientConfig(Http.client.withChannelFactory(InMemoryChannelFactory(handler)))
          .connect("http://test/mcp")
      )
      .use { client =>
        client
          .callTool("hello", JSONObject(Seq("name" -> JSONString("a"))))
          .map { _ =>
            received.result().exists(_.contains("_meta")) shouldBe false
          }
      }
  }

end MCPTelemetryTest
