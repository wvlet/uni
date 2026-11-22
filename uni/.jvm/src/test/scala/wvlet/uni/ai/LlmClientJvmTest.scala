package wvlet.uni.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import wvlet.uni.ai.anthropic as an
import wvlet.uni.ai.gemini as gm
import wvlet.uni.ai.openai as oa
import wvlet.uni.json.JSON
import wvlet.uni.rx.OnCompletion
import wvlet.uni.rx.OnFailure
import wvlet.uni.rx.OnError
import wvlet.uni.rx.OnNext
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxEvent
import wvlet.uni.rx.RxRunner
import wvlet.uni.cloud.AwsCredentialsProvider
import wvlet.uni.telemetry.{MetricExporter, SpanExporter, SpanKind, SpanStatus, Telemetry}
import wvlet.uni.test.UniTest

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
  * The vendor clients against a loopback server that answers like each vendor: request paths, auth
  * headers, bodies, SSE responses, and error bodies
  */
class LlmClientJvmTest extends UniTest:

  private case class Received(
      method: String,
      path: String,
      query: String,
      headers: Map[String, String],
      body: String
  )

  private val last   = AtomicReference[Received]()
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.createContext(
    "/",
    exchange =>
      try handle(exchange)
      finally exchange.close()
  )

  server.start()

  private def base = s"http://127.0.0.1:${server.getAddress.getPort}"

  override protected def afterAll: Unit = server.stop(0)

  private def respond(
      ex: HttpExchange,
      status: Int,
      contentType: String,
      chunks: Seq[Array[Byte]]
  ): Unit =
    ex.getResponseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(status, 0)
    val out = ex.getResponseBody
    chunks.foreach { c =>
      out.write(c)
      out.flush()
    }

  private def sse(events: (String, String)*): Seq[Array[Byte]] = events.map { (name, data) =>
    val e =
      if name.isEmpty then
        ""
      else
        s"event: ${name}\n"
    s"${e}data: ${data}\n\n".getBytes(UTF_8)
  }

  private def handle(ex: HttpExchange): Unit =
    val path = ex.getRequestURI.getRawPath
    last.set(
      Received(
        ex.getRequestMethod,
        path,
        Option(ex.getRequestURI.getRawQuery).getOrElse(""),
        ex.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap,
        String(ex.getRequestBody.readAllBytes(), UTF_8)
      )
    )
    val streaming = last.get().body.contains("\"stream\":true")
    // Bedrock serves the Anthropic and OpenAI APIs under these prefixes
    path.stripPrefix("/anthropic").stripPrefix("/openai") match
      case "/v1/messages" if last.get().headers.get("x-api-key").contains("overloaded") =>
        respond(
          ex,
          529,
          "application/json",
          Seq(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
              .getBytes(UTF_8)
          )
        )
      case "/v1/messages" if streaming =>
        respond(
          ex,
          200,
          "text/event-stream",
          sse(
            "message_start" ->
              """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "content_block_start" ->
              """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" ->
              """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
            "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
            "message_delta"      ->
              """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}""",
            "message_stop" -> """{"type":"message_stop"}"""
          )
        )
      case "/v1/messages" =>
        respond(
          ex,
          200,
          "application/json",
          Seq(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude","content":[{"type":"text","text":"Hello"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":2}}"""
              .getBytes(UTF_8)
          )
        )
      case "/v1/messages/count_tokens" =>
        respond(ex, 200, "application/json", Seq("""{"input_tokens":12}""".getBytes(UTF_8)))
      case "/v1/responses" =>
        respond(
          ex,
          200,
          "text/event-stream",
          sse(
            "response.created" ->
              """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","created_at":1,"status":"in_progress","model":"gpt","output":[]}}""",
            "response.content_part.added" ->
              """{"type":"response.content_part.added","sequence_number":1,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
            "response.output_text.delta" ->
              """{"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"Yo"}""",
            "response.content_part.done" ->
              """{"type":"response.content_part.done","sequence_number":3,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Yo","annotations":[]}}""",
            "response.completed" ->
              """{"type":"response.completed","sequence_number":4,"response":{"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt","output":[],"usage":{"input_tokens":5,"output_tokens":1,"total_tokens":6}}}"""
          )
        )
      case "/v1/chat/completions" =>
        respond(
          ex,
          200,
          "text/event-stream",
          sse(
            "" ->
              """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"A"},"finish_reason":null}]}""",
            "" ->
              """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "" ->
              """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""",
            "" -> "[DONE]"
          )
        )
      case p if p.startsWith("/v1beta/models/") =>
        respond(
          ex,
          200,
          "text/event-stream",
          sse(
            "" ->
              """{"candidates":[{"content":{"role":"model","parts":[{"text":"Gem"}]}}],"modelVersion":"gemini-2.5-flash"}""",
            "" ->
              """{"candidates":[{"content":{"role":"model","parts":[{"text":"ini"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":2,"totalTokenCount":4}}"""
          )
        )
      case _ =>
        respond(ex, 404, "application/json", Seq("""{"message":"not found"}""".getBytes(UTF_8)))
    end match

  end handle

  private def runStream[A](stream: Rx[A]): (Seq[A], RxEvent) =
    val values   = CopyOnWriteArrayList[A]()
    val terminal = LinkedBlockingQueue[RxEvent]()
    val c        =
      RxRunner.run(stream) {
        case OnNext(v) =>
          values.add(v.asInstanceOf[A])
        case other =>
          terminal.add(other)
      }
    try
      val e = terminal.poll(10, TimeUnit.SECONDS)
      if e == null then
        fail("stream did not terminate within ten seconds")
      (values.asScala.toSeq, e)
    finally
      c.cancel

  private def single[A](rx: Rx[A]): A =
    val (values, terminal) = runStream(rx)
    terminal shouldBe OnCompletion
    values.head

  private val anthropic = an.AnthropicClient(
    an.AnthropicConfig(an.AnthropicHost.Direct("key-1", base)).withBetas(Seq("b1"))
  )

  private val request = an.MessagesRequest("claude", 100, Seq(an.Message.user("hi")))

  test("Anthropic messages, count_tokens, and streams on the Anthropic API") {
    single(anthropic.messages(request)).text shouldBe "Hello"
    val r = last.get()
    r.headers.get("x-api-key") shouldBe Some("key-1")
    r.headers.get("anthropic-version") shouldBe Some("2023-06-01")
    r.headers.get("anthropic-beta") shouldBe Some("b1")
    r.body.contains("\"stream\"") shouldBe false

    single(anthropic.countTokens(request)).inputTokens shouldBe 12
    last.get().body.contains("max_tokens") shouldBe false

    val (events, terminal) = runStream(anthropic.stream(request))
    terminal shouldBe OnCompletion
    val acc = an.MessageAccumulator()
    events.foreach(acc.add)
    acc.result.text shouldBe "Hi"
  }

  test("an Anthropic error body becomes an LlmApiException with the error type") {
    val client = an.AnthropicClient(an.AnthropicConfig(an.AnthropicHost.Direct("overloaded", base)))
    val (_, terminal) = runStream(client.messages(request))
    terminal shouldMatch { case OnFailure(e: LlmApiException) =>
      e.statusCode shouldBe 529
      e.errorType shouldBe Some("overloaded_error")
      e.isRetryable shouldBe true
    }
  }

  test("a neutral request served by OpenAI Responses and Chat Completions") {
    val llm        = LlmRequest("gpt", Seq(LlmMessage.user("hi")))
    val responses  = oa.OpenAIClient(oa.OpenAIConfig(Some("sk-1"), s"${base}/v1"))
    val acc        = LlmStreamAccumulator()
    val (parts, t) = runStream(responses.stream(llm))
    t shouldBe OnCompletion
    parts.foreach(acc.add)
    acc.result.text shouldBe "Yo"
    acc.result.usage.inputTokens shouldBe 5
    last.get().headers.get("authorization") shouldBe Some("Bearer sk-1")

    val chat = oa.OpenAIClient(
      oa.OpenAIConfig(Some("sk-2"), s"${base}/v1", useChatCompletions = true)
    )
    val chatAcc      = LlmStreamAccumulator()
    val (cparts, ct) = runStream(chat.stream(llm))
    ct shouldBe OnCompletion
    cparts.foreach(chatAcc.add)
    chatAcc.result.text shouldBe "A"
    chatAcc.result.usage.inputTokens shouldBe 4
    JSON.parseAny(last.get().body).toJSON.contains("\"include_usage\":true") shouldBe true
  }

  test("Gemini streamGenerateContent with alt=sse and the API key header") {
    val client = gm.GeminiClient(
      gm.GeminiConfig(gm.GeminiHost.AiStudio("g-key", s"${base}/v1beta"))
    )
    val (chunks, t) = runStream(
      client.streamGenerateContent(
        "gemini-2.5-flash",
        gm.GenerateContentRequest(Seq(gm.Content.user("hi")))
      )
    )
    t shouldBe OnCompletion
    val acc = gm.GenerateContentAccumulator()
    chunks.foreach(acc.add)
    acc.result.text shouldBe "Gemini"
    val r = last.get()
    r.path shouldBe "/v1beta/models/gemini-2.5-flash:streamGenerateContent"
    r.query shouldBe "alt=sse"
    r.headers.get("x-goog-api-key") shouldBe Some("g-key")
  }

  // The clients build https URLs; point a Bedrock host at the loopback server over http
  private def bedrockHttp(host: String) =
    wvlet
      .uni
      .http
      .Http
      .client
      .noRetry
      .withRequestFilter(r => r.withUri(r.uri.replace(s"https://${host}", base)))
      .newAsyncClient

  private def bedrockHost = s"127.0.0.1:${server.getAddress.getPort}"

  test("Claude on Bedrock through the Anthropic API with an API key") {
    val client = an.AnthropicClient(
      an.AnthropicConfig(
          an.AnthropicHost.Bedrock("us-east-1", BedrockAuth.ApiKey("bk"), Some(bedrockHost))
        )
        .withBetas(Seq("b1")),
      bedrockHttp(bedrockHost)
    )
    val req = request.copy(model = "us.anthropic.claude-sonnet-5")
    single(client.messages(req)).text shouldBe "Hello"
    val r = last.get()
    r.path shouldBe "/anthropic/v1/messages"
    r.headers.get("x-api-key") shouldBe Some("bk")
    r.headers.get("anthropic-version") shouldBe Some("2023-06-01")
    r.headers.get("anthropic-beta") shouldBe Some("b1")
    r.headers.contains("authorization") shouldBe false
    JSON.parseAny(r.body).toJSON.contains("\"model\":\"us.anthropic.claude-sonnet-5\"") shouldBe
      true

    val (events, t) = runStream(client.stream(req))
    t shouldBe OnCompletion
    last.get().path shouldBe "/anthropic/v1/messages"
    val acc = an.MessageAccumulator()
    events.foreach(acc.add)
    acc.result.text shouldBe "Hi"

    val (_, ct) = runStream(client.countTokens(req))
    ct shouldMatch { case OnFailure(_: UnsupportedOperationException) =>
    }
    client.close()
  }

  test("Claude on Bedrock signs requests with SigV4 for AWS credentials") {
    val client = an.AnthropicClient(
      an.AnthropicConfig(
        an.AnthropicHost
          .Bedrock(
            "us-west-2",
            BedrockAuth.Credentials(AwsCredentialsProvider.static("AKIDEXAMPLE", "secret")),
            Some(bedrockHost)
          )
      ),
      bedrockHttp(bedrockHost)
    )
    val (events, t) = runStream(client.stream(request))
    t shouldBe OnCompletion
    events.nonEmpty shouldBe true
    val r    = last.get()
    val auth = r.headers.getOrElse("authorization", "")
    r.path shouldBe "/anthropic/v1/messages"
    auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/") shouldBe true
    auth.contains("/us-west-2/bedrock/aws4_request") shouldBe true
    r.headers.contains("x-amz-date") shouldBe true
    r.headers.contains("x-api-key") shouldBe false
    client.close()
  }

  test("OpenAI models on Bedrock through Responses and Chat Completions") {
    val llm    = LlmRequest("us.openai.gpt-5.6-sol", Seq(LlmMessage.user("hi")))
    val client = oa.OpenAIClient(
      oa.OpenAIConfig.bedrock("us-east-1", BedrockAuth.ApiKey("bk"), Some(bedrockHost)),
      bedrockHttp(bedrockHost)
    )
    val acc        = LlmStreamAccumulator()
    val (parts, t) = runStream(client.stream(llm))
    t shouldBe OnCompletion
    parts.foreach(acc.add)
    acc.result.text shouldBe "Yo"
    last.get().path shouldBe "/openai/v1/responses"
    last.get().headers.get("authorization") shouldBe Some("Bearer bk")

    val signed = oa.OpenAIClient(
      oa.OpenAIConfig
        .bedrock(
          "us-east-1",
          BedrockAuth.Credentials(AwsCredentialsProvider.static("AKIDEXAMPLE", "secret")),
          Some(bedrockHost)
        )
        .withUseChatCompletions(true),
      bedrockHttp(bedrockHost)
    )
    val chatAcc      = LlmStreamAccumulator()
    val (cparts, ct) = runStream(signed.stream(llm.copy(model = "openai.gpt-oss-120b-1:0")))
    ct shouldBe OnCompletion
    cparts.foreach(chatAcc.add)
    chatAcc.result.text shouldBe "A"
    last.get().path shouldBe "/openai/v1/chat/completions"
    last
      .get()
      .headers
      .getOrElse("authorization", "")
      .contains("/us-east-1/bedrock/aws4_request") shouldBe true
    client.close()
    signed.close()
  }

  // Telemetry: GenAI spans and metrics

  private class Traced:
    val spans     = SpanExporter.inMemory()
    val metrics   = MetricExporter.inMemory()
    val telemetry = Telemetry().withExporter(spans).withMetricExporter(metrics)
    def http      = wvlet.uni.http.Http.client.noRetry.withTelemetry(telemetry).newAsyncClient

    def genAi = spans.spans.filter(_.attributes.contains("gen_ai.operation.name"))

    // collect() returns what arrived since the last call, so keep everything seen
    private val collected = scala
      .collection
      .mutable
      .ArrayBuffer
      .empty[wvlet.uni.telemetry.HistogramData]

    def histogram(name: String) =
      collected ++= metrics.collect()
      collected.toSeq.filter(_.instrument.name == name)

  test("an Anthropic call is a GenAI span over its HTTP span, with usage and metrics") {
    val t      = Traced()
    val client = an.AnthropicClient(
      an.AnthropicConfig(an.AnthropicHost.Direct("key-1", base)),
      t.http
    )
    single(client.messages(request.copy(temperature = Some(0.5)))).text shouldBe "Hello"
    val span = t.genAi.head
    span.name shouldBe "chat claude"
    span.kind shouldBe SpanKind.Client
    span.attributes("gen_ai.provider.name") shouldBe "anthropic"
    span.attributes("gen_ai.request.model") shouldBe "claude"
    span.attributes("gen_ai.request.max_tokens") shouldBe 100L
    span.attributes("gen_ai.request.temperature") shouldBe 0.5
    span.attributes("gen_ai.response.model") shouldBe "claude"
    span.attributes("gen_ai.response.id") shouldBe "msg_1"
    span.attributes("gen_ai.response.finish_reasons") shouldBe "end_turn"
    span.attributes("gen_ai.usage.input_tokens") shouldBe 3L
    span.attributes("gen_ai.usage.output_tokens") shouldBe 2L
    span.attributes("server.address") shouldBe "127.0.0.1"
    // No content on the span
    span.attributes.values.exists(_ == "hi") shouldBe false
    span.attributes.values.exists(_ == "Hello") shouldBe false
    val httpSpan = t.spans.spans.find(_.attributes.contains("http.request.method")).get
    httpSpan.parentSpanId shouldBe Some(span.spanId)
    httpSpan.traceId shouldBe span.traceId
    // Propagated to the vendor
    last.get().headers.get("traceparent").exists(_.contains(span.traceId)) shouldBe true

    val duration = t.histogram("gen_ai.client.inference.duration")
    duration.map(_.count).sum shouldBe 1L
    duration.head.attributes("gen_ai.provider.name") shouldBe "anthropic"
    duration.head.attributes("gen_ai.response.model") shouldBe "claude"
    duration.head.attributes.contains("gen_ai.response.id") shouldBe false
    t.histogram("gen_ai.client.inference.operation.input_tokens").head.sum shouldBe 3.0
    t.histogram("gen_ai.client.inference.operation.output_tokens").head.sum shouldBe 2.0
    client.close()
  }

  test("an Anthropic stream ends its span with the stream, under the caller's span") {
    val t      = Traced()
    val client = an.AnthropicClient(
      an.AnthropicConfig(an.AnthropicHost.Direct("key-1", base)),
      t.http
    )
    val parent = t.telemetry.startSpan("handler")
    val rx     = Telemetry.withSpan(parent)(client.stream(request))
    // The span starts only when the stream runs
    t.genAi shouldBe Seq.empty
    val (_, terminal) = runStream(rx)
    terminal shouldBe OnCompletion
    parent.end()
    val span = t.genAi.head
    span.parentSpanId shouldBe Some(parent.context.spanId)
    span.attributes("gen_ai.request.stream") shouldBe true
    span.attributes("gen_ai.response.id") shouldBe "msg_1"
    span.attributes("gen_ai.response.finish_reasons") shouldBe "end_turn"
    span.attributes("gen_ai.usage.input_tokens") shouldBe 3L
    span.attributes("gen_ai.usage.output_tokens") shouldBe 2L
    // Running the stream again is a new call with a new span
    runStream(rx)._2 shouldBe OnCompletion
    t.genAi.map(_.spanId).distinct.size shouldBe 2
    client.close()
  }

  test("a failed LLM call records the vendor's error type") {
    val t      = Traced()
    val client = an.AnthropicClient(
      an.AnthropicConfig(an.AnthropicHost.Direct("overloaded", base)),
      t.http
    )
    runStream(client.messages(request))._2 shouldMatch { case OnFailure(_) =>
    }
    val span = t.genAi.head
    span.status shouldBe SpanStatus.Error
    span.attributes("error.type") shouldBe "overloaded_error"
    t.histogram("gen_ai.client.inference.duration").head.attributes("error.type") shouldBe
      "overloaded_error"
    client.close()
  }

  test("OpenAI and Gemini streams report provider, response, usage, and finish reasons") {
    val t         = Traced()
    val llm       = LlmRequest("gpt", Seq(LlmMessage.user("hi")))
    val responses = oa.OpenAIClient(oa.OpenAIConfig(Some("sk-1"), s"${base}/v1"), t.http)
    runStream(responses.stream(llm))._2 shouldBe OnCompletion
    val r = t.genAi.last
    r.name shouldBe "chat gpt"
    r.attributes("gen_ai.provider.name") shouldBe "openai"
    r.attributes("gen_ai.response.id") shouldBe "resp_1"
    r.attributes("gen_ai.response.finish_reasons") shouldBe "stop"
    r.attributes("gen_ai.usage.input_tokens") shouldBe 5L

    val chat = oa.OpenAIClient(
      oa.OpenAIConfig(Some("sk-2"), s"${base}/v1", useChatCompletions = true),
      t.http
    )
    runStream(chat.stream(llm))._2 shouldBe OnCompletion
    val c = t.genAi.last
    c.attributes("gen_ai.response.id") shouldBe "c1"
    c.attributes("gen_ai.response.model") shouldBe "m"
    c.attributes("gen_ai.response.finish_reasons") shouldBe "stop"
    c.attributes("gen_ai.usage.input_tokens") shouldBe 4L
    c.attributes("gen_ai.usage.output_tokens") shouldBe 1L

    val gemini = gm.GeminiClient(
      gm.GeminiConfig(gm.GeminiHost.AiStudio("g-key", s"${base}/v1beta")),
      t.http
    )
    runStream(
      gemini.streamGenerateContent(
        "gemini-2.5-flash",
        gm.GenerateContentRequest(Seq(gm.Content.user("hi")))
      )
    )._2 shouldBe OnCompletion
    val g = t.genAi.last
    g.name shouldBe "generate_content gemini-2.5-flash"
    g.attributes("gen_ai.provider.name") shouldBe "gcp.gemini"
    g.attributes("gen_ai.response.model") shouldBe "gemini-2.5-flash"
    g.attributes("gen_ai.response.finish_reasons") shouldBe "STOP"
    g.attributes("gen_ai.usage.input_tokens") shouldBe 2L
    g.attributes("gen_ai.usage.output_tokens") shouldBe 2L
    Seq(responses, chat, gemini).foreach(_.close())
  }

  test("Bedrock reports aws.bedrock as the provider") {
    val t    = Traced()
    val http =
      wvlet
        .uni
        .http
        .Http
        .client
        .noRetry
        .withTelemetry(t.telemetry)
        .withRequestFilter(r => r.withUri(r.uri.replace(s"https://${bedrockHost}", base)))
        .newAsyncClient
    val client = an.AnthropicClient(
      an.AnthropicConfig(
        an.AnthropicHost.Bedrock("us-east-1", BedrockAuth.ApiKey("bk"), Some(bedrockHost))
      ),
      http
    )
    single(client.messages(request)).text shouldBe "Hello"
    t.genAi.head.attributes("gen_ai.provider.name") shouldBe "aws.bedrock"
    client.close()
  }

  test("metrics-only telemetry records GenAI metrics without spans or traceparent") {
    val metrics   = MetricExporter.inMemory()
    val telemetry = Telemetry().withMetricExporter(metrics)
    val client    = an.AnthropicClient(
      an.AnthropicConfig(an.AnthropicHost.Direct("key-1", base)),
      wvlet.uni.http.Http.client.noRetry.withTelemetry(telemetry).newAsyncClient
    )
    single(client.messages(request)).text shouldBe "Hello"
    last.get().headers.contains("traceparent") shouldBe false
    metrics
      .collect()
      .filter(_.instrument.name == "gen_ai.client.inference.operation.output_tokens")
      .map(_.sum) shouldBe Seq(2.0)
    client.close()
  }

end LlmClientJvmTest
