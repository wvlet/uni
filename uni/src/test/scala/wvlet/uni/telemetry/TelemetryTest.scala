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

import wvlet.uni.test.UniTest

class TelemetryTest extends UniTest:
  private val validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val validSpanId  = "00f067aa0ba902b7"
  private val valid        = s"00-${validTraceId}-${validSpanId}-01"

  test("parse a traceparent header") {
    val ctx = TraceContext.parse(valid, Some("vendor=abc")).get
    ctx.traceId shouldBe validTraceId
    ctx.spanId shouldBe validSpanId
    ctx.sampled shouldBe true
    ctx.traceState shouldBe Some("vendor=abc")
    ctx.toTraceParent shouldBe valid
    TraceContext.parse(s"00-${validTraceId}-${validSpanId}-00").get.sampled shouldBe false
  }

  test("reject malformed traceparent headers") {
    val invalid = Seq(
      "",
      "garbage",
      s"00-${"0" * 32}-${validSpanId}-01",
      s"00-${validTraceId}-${"0" * 16}-01",
      s"00-${validTraceId.toUpperCase}-${validSpanId}-01",
      s"ff-${validTraceId}-${validSpanId}-01",
      s"00-${validTraceId}-${validSpanId}-01-extra",
      s"00-${validTraceId}-${validSpanId}-0x",
      s"00_${validTraceId}-${validSpanId}-01"
    )
    invalid.foreach { s =>
      TraceContext.parse(s) shouldBe None
    }
  }

  test("accept a later version with extra fields") {
    TraceContext.parse(s"01-${validTraceId}-${validSpanId}-01-future").map(_.traceId) shouldBe
      Some(validTraceId)
  }

  test("generate valid ids") {
    val ctx = TraceContext.newRoot(sampled = true)
    TraceContext.parse(ctx.toTraceParent) shouldBe Some(ctx)
    ctx.newChild.traceId shouldBe ctx.traceId
    ctx.newChild.spanId shouldNotBe ctx.spanId
  }

  test("a disabled Telemetry records nothing") {
    val span = Telemetry.noop.startSpan("work")
    span.isRecording shouldBe false
    Telemetry.noop.isEnabled shouldBe false
  }

  test("record nested spans with parent links") {
    val exporter  = SpanExporter.inMemory()
    val telemetry = Telemetry().withServiceName("test").withExporter(exporter)
    telemetry.trace("outer") { outer =>
      outer.setAttribute("count", 3)
      telemetry.trace("inner")(_ => ())
    }
    val Seq(inner, outer) = exporter.spans
    inner.name shouldBe "inner"
    outer.name shouldBe "outer"
    inner.traceId shouldBe outer.traceId
    inner.parentSpanId shouldBe Some(outer.spanId)
    outer.parentSpanId shouldBe None
    outer.attributes("count") shouldBe 3L
    Telemetry.currentSpan shouldBe None
    telemetry.resource("service.name") shouldBe "test"
  }

  test("record an exception and rethrow it") {
    val exporter  = SpanExporter.inMemory()
    val telemetry = Telemetry().withExporter(exporter)
    intercept[IllegalStateException] {
      telemetry.trace("fail")(_ => throw IllegalStateException("boom"))
    }
    val span = exporter.spans.head
    span.status shouldBe SpanStatus.Error
    span.statusMessage shouldBe Some("boom")
    span.events.map(_.name) shouldBe Seq("exception")
    span.events.head.attributes("exception.type") shouldBe classOf[IllegalStateException].getName
  }

  test("export a span once and ignore changes after end") {
    val exporter = SpanExporter.inMemory()
    val span     = Telemetry().withExporter(exporter).startSpan("once")
    span.end()
    span.setAttribute("late", true)
    span.end()
    exporter.spans.size shouldBe 1
    exporter.spans.head.attributes.contains("late") shouldBe false
  }

  test("follow the parent's sampled flag and the sample ratio") {
    val exporter  = SpanExporter.inMemory()
    val telemetry = Telemetry().withExporter(exporter)
    val unsampled = TraceContext.parse(s"00-${validTraceId}-${validSpanId}-00")
    val child     = telemetry.startSpan("child", parent = unsampled)
    child.isRecording shouldBe false
    // An unsampled span still propagates the trace
    child.context.traceId shouldBe validTraceId

    val none = telemetry.withSampleRatio(0.0).startSpan("root")
    none.isRecording shouldBe false
    none.end()
    exporter.spans shouldBe Seq.empty
  }

  test("withContext makes a remote context the parent") {
    val exporter  = SpanExporter.inMemory()
    val telemetry = Telemetry().withExporter(exporter)
    val remote    = TraceContext.parse(valid).get
    Telemetry.withContext(remote) {
      telemetry.trace("job")(_ => ())
    }
    exporter.spans.head.traceId shouldBe validTraceId
    exporter.spans.head.parentSpanId shouldBe Some(validSpanId)
  }

  test("a failing exporter doesn't fail the traced code") {
    val failing: SpanExporter = _ => throw IllegalStateException("exporter down")
    val logs                  = captureLogs {
      Telemetry().withExporter(failing).trace("work")(_ => 1) shouldBe 1
    }
    logs.warnings shouldContain "exporter down"
  }

end TelemetryTest
