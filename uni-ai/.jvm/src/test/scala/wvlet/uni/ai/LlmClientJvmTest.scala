package wvlet.uni.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import wvlet.uni.ai.anthropic as an
import wvlet.uni.ai.bedrock as br
import wvlet.uni.ai.gemini as gm
import wvlet.uni.ai.openai as oa
import wvlet.uni.json.JSON
import wvlet.uni.rx.OnCompletion
import wvlet.uni.rx.OnError
import wvlet.uni.rx.OnNext
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxEvent
import wvlet.uni.rx.RxRunner
import wvlet.uni.storage.AwsCredentialsProvider
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
  * headers, bodies, SSE and event-stream responses, and error bodies
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
    path match
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
      case p if p.endsWith("/converse-stream") =>
        val frames =
          Seq(
            br.EventStreamFrame.event("messageStart", """{"role":"assistant"}"""),
            br.EventStreamFrame
              .event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"Bed"}}"""),
            br.EventStreamFrame
              .event("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"rock"}}"""),
            br.EventStreamFrame.event("contentBlockStop", """{"contentBlockIndex":0}"""),
            br.EventStreamFrame.event("messageStop", """{"stopReason":"end_turn"}"""),
            br.EventStreamFrame
              .event("metadata", """{"usage":{"inputTokens":3,"outputTokens":2,"totalTokens":5}}""")
          ).flatten.toArray
        // Split frames across chunks
        respond(ex, 200, "application/vnd.amazon.eventstream", frames.grouped(11).toSeq)
      case p if p.endsWith("/converse") =>
        respond(
          ex,
          200,
          "application/json",
          Seq(
            """{"output":{"message":{"role":"assistant","content":[{"text":"Conv"}]}},"stopReason":"end_turn","usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2}}"""
              .getBytes(UTF_8)
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
    terminal shouldMatch { case OnError(e: LlmApiException) =>
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

  test("Bedrock converse and converse-stream with an API key") {
    val host   = s"127.0.0.1:${server.getAddress.getPort}"
    val client = br.BedrockClient(br.BedrockConfig("us-east-1", BedrockAuth.ApiKey("bk")))
    // The client builds https URLs; point it at the loopback server over http instead
    val http =
      wvlet
        .uni
        .http
        .Http
        .client
        .noRetry
        .withRequestFilter(r => r.withUri(r.uri.replace(s"https://${host}", base)))
        .newAsyncClient
    val local = br.BedrockClient(
      br.BedrockConfig("us-east-1", BedrockAuth.ApiKey("bk"), Some(host)),
      http
    )
    single(local.converse("anthropic.claude:0", br.ConverseRequest(Seq(br.Message.user("hi")))))
      .text shouldBe "Conv"
    last.get().path shouldBe "/model/anthropic.claude%3A0/converse"
    last.get().headers.get("authorization") shouldBe Some("Bearer bk")

    val (events, t) = runStream(
      local.converseStream("anthropic.claude:0", br.ConverseRequest(Seq(br.Message.user("hi"))))
    )
    t shouldBe OnCompletion
    val acc = br.ConverseAccumulator()
    events.foreach(acc.add)
    acc.result.text shouldBe "Bedrock"
    acc.result.usage.totalTokens shouldBe 5
    client.close()
  }

  test("Bedrock requests are SigV4-signed with AWS credentials") {
    val host = s"127.0.0.1:${server.getAddress.getPort}"
    val http =
      wvlet
        .uni
        .http
        .Http
        .client
        .noRetry
        .withRequestFilter(r => r.withUri(r.uri.replace(s"https://${host}", base)))
        .newAsyncClient
    val client = br.BedrockClient(
      br.BedrockConfig(
        "us-west-2",
        BedrockAuth.Credentials(AwsCredentialsProvider.static("AKIDEXAMPLE", "secret")),
        Some(host)
      ),
      http
    )
    single(client.converse("m:1", br.ConverseRequest(Seq(br.Message.user("hi")))))
    val auth = last.get().headers.getOrElse("authorization", "")
    auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/") shouldBe true
    auth.contains("/us-west-2/bedrock/aws4_request") shouldBe true
    last.get().headers.contains("x-amz-date") shouldBe true
  }

end LlmClientJvmTest
