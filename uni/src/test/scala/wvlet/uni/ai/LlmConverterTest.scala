package wvlet.uni.ai

import wvlet.uni.ai.anthropic as an
import wvlet.uni.ai.gemini as gm
import wvlet.uni.ai.openai as oa
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.test.UniTest

/**
  * Cross-vendor conversion: a Claude Code style Anthropic request served by other vendors, and
  * other vendors' streams turned back into Anthropic events.
  */
class LlmConverterTest extends UniTest:

  private val claudeCodeRequest = JsonCodec[an.MessagesRequest].fromJson("""{"model":"claude-sonnet-4-5","max_tokens":16000,"stream":true,
      |"system":[{"type":"text","text":"You are Claude Code.","cache_control":{"type":"ephemeral"}}],
      |"messages":[
      | {"role":"user","content":[{"type":"text","text":"List files"}]},
      | {"role":"assistant","content":[
      |   {"type":"thinking","thinking":"Use ls.","signature":"anthropic-sig"},
      |   {"type":"text","text":"Running ls."},
      |   {"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"ls"}}]},
      | {"role":"user","content":[
      |   {"type":"tool_result","tool_use_id":"toolu_01","content":"a.txt\nb.txt"},
      |   {"type":"text","text":"Thanks"}]}
      |],
      |"tools":[{"name":"Bash","description":"Run a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}],
      |"thinking":{"type":"enabled","budget_tokens":4000},
      |"metadata":{"user_id":"u1"}}""".stripMargin)

  private val llmRequest = an.AnthropicConverter.toLlmRequest(claudeCodeRequest)

  test("Anthropic request to the neutral model splits tool results into their own turn") {
    llmRequest.messages.map(_.role) shouldBe
      Seq(LlmRole.User, LlmRole.Assistant, LlmRole.Tool, LlmRole.User)
    llmRequest.messages(2).parts.head shouldMatch { case r: LlmPart.ToolResult =>
      r.text shouldBe "a.txt\nb.txt"
    }
    llmRequest.reasoning shouldBe Some(ReasoningOptions(budgetTokens = Some(4000)))
    llmRequest.stream shouldBe true
  }

  test("Anthropic request round-trips through the neutral model") {
    val back = an.AnthropicConverter.fromLlmRequest(llmRequest)
    TestJson.normalize(JsonCodec[an.MessagesRequest].encode(back)) shouldBe
      TestJson.normalize(JsonCodec[an.MessagesRequest].encode(claudeCodeRequest))
  }

  test("to OpenAI Responses: instructions, function items, and effort") {
    val r = oa.ResponsesConverter.fromLlmRequest(llmRequest.withModel("gpt-5.1"))
    r.model shouldBe "gpt-5.1"
    r.instructions shouldBe Some("You are Claude Code.")
    r.reasoning shouldBe Some(oa.ReasoningConfig(Some("medium"), Some("auto")))
    r.store shouldBe Some(false)
    r.include shouldBe Seq(oa.ResponsesConverter.EncryptedReasoning)
    r.temperature shouldBe None
    // Anthropic-signed thinking cannot be decrypted by OpenAI, so it is not sent
    r.input
      .map {
        case m: oa.Item.Message =>
          s"message:${m.role}"
        case _: oa.Item.FunctionCall =>
          "function_call"
        case _: oa.Item.FunctionCallOutput =>
          "function_call_output"
        case _ =>
          "other"
      } shouldBe
      Seq(
        "message:user",
        "message:assistant",
        "function_call",
        "function_call_output",
        "message:user"
      )
    r.input(2) shouldBe oa.Item.FunctionCall("toolu_01", "Bash", """{"command":"ls"}""")
  }

  test("a Responses client gets Anthropic signatures in encrypted_content and returns them") {
    val claude = LlmResponse(
      "msg_1",
      "claude",
      Seq(LlmPart.Reasoning("Use ls.", Some("anthropic-sig")), LlmPart.Text("ok")),
      FinishReason.stop
    )
    val res       = oa.ResponsesConverter.fromLlmResponse(claude)
    val reasoning =
      res
        .output
        .collectFirst { case r: oa.Item.Reasoning =>
          r
        }
        .get
    reasoning.encryptedContent.exists(LlmConvert.Carrier.isCarrier) shouldBe true
    // The client sends the item back as input on the next turn
    val next = oa.ResponsesConverter.toLlmRequest(oa.ResponsesRequest("gpt", Seq(reasoning)))
    next.messages.head.parts.head shouldBe LlmPart.Reasoning("Use ls.", Some("anthropic-sig"))
  }

  test("requests to the Anthropic API carry no carrier signatures") {
    val history = LlmRequest(
      "claude",
      Seq(
        LlmMessage.user("q"),
        LlmMessage(
          LlmRole.Assistant,
          Seq(
            LlmPart.Reasoning(
              "r",
              providerMetadata = ProviderMetadata.of(
                ProviderMetadata.OpenAI,
                Json.field("encryptedContent", "gAAA")
              )
            ),
            LlmPart.ToolCall(
              "call_1",
              "f",
              JSONObject.empty,
              providerMetadata = ProviderMetadata.of(
                ProviderMetadata.Gemini,
                Json.field("thoughtSignature", "c2ln")
              )
            )
          )
        )
      )
    )
    val req = an.AnthropicConverter.fromLlmRequest(history)
    req.messages(1).content.map(_.blockType) shouldBe Seq("tool_use")
  }

  test("redacted reasoning streams back as redacted_thinking") {
    val enc    = an.AnthropicConverter.StreamEncoder()
    val events = Seq(
      LlmStreamPart.Start("m", "claude"),
      LlmStreamPart.ReasoningStart("0"),
      LlmStreamPart.ReasoningEnd("0", redactedData = Some("EmwKAhgB")),
      LlmStreamPart.Finish(FinishReason.stop)
    ).flatMap(enc.encode)
    val acc = an.MessageAccumulator()
    events.foreach(acc.add)
    acc.result.content shouldBe Seq(an.ContentBlock.RedactedThinking("EmwKAhgB"))
  }

  test("to Chat Completions: system, tool_calls, and tool messages") {
    val r = oa.ChatCompletionsConverter.fromLlmRequest(llmRequest.withModel("gpt-4.1"))
    r.messages.map(_.role) shouldBe Seq("system", "user", "assistant", "tool", "user")
    r.messages(2).toolCalls shouldBe
      Seq(oa.ChatToolCall("toolu_01", "Bash", """{"command":"ls"}"""))
    r.messages(2).text shouldBe "Running ls."
    r.messages(3).toolCallId shouldBe Some("toolu_01")
    r.reasoningEffort shouldBe Some("medium")
    r.includeUsage shouldBe Some(true)
    r.maxCompletionTokens shouldBe Some(16000)
  }

  test("to Gemini: roles, function response names, schema, and a signature placeholder") {
    val r = gm.GeminiConverter.fromLlmRequest(llmRequest)
    r.contents.map(_.role.get) shouldBe Seq("user", "model", "user")
    r.systemInstruction.map(_.text) shouldBe Some("You are Claude Code.")
    val call =
      r.contents(1)
        .parts
        .collectFirst { case f: gm.Part.FunctionCall =>
          f
        }
        .get
    call.name shouldBe "Bash"
    call.thoughtSignature shouldBe Some(gm.GeminiConverter.SkipThoughtSignature)
    // Anthropic-signed thinking is not replayed to Gemini
    r.contents(1)
      .parts
      .exists {
        case t: gm.Part.Text =>
          t.isThought
        case _ =>
          false
      } shouldBe false
    r.contents(2).parts.head shouldBe
      gm.Part
        .FunctionResponse("Bash", Json.obj(Json.field("result", "a.txt\nb.txt")), Some("toolu_01"))
    r.tools.head.functionDeclarations.head.parametersJsonSchema.isDefined shouldBe true
    r.generationConfig.flatMap(_.thinkingConfig) shouldBe
      Some(gm.ThinkingConfig(thinkingBudget = Some(4000), includeThoughts = Some(true)))
  }

  // ------------------------------------------------------------------ streams

  private def toAnthropic(parts: Seq[LlmStreamPart]): an.MessagesResponse =
    val enc    = an.AnthropicConverter.StreamEncoder()
    val events = parts.flatMap(enc.encode) ++ enc.finish()
    // Every event must survive the wire
    val wire = events.map(e => an.StreamEvent.fromServerSentEvent(e.toServerSentEvent))
    wire.head shouldMatch { case _: an.StreamEvent.MessageStart =>
    }
    wire.last shouldBe an.StreamEvent.MessageStop
    val acc = an.MessageAccumulator()
    wire.foreach(acc.add)
    acc.result

  test("OpenAI Responses stream to Anthropic events") {
    val events = Seq(
      """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","created_at":1,"status":"in_progress","model":"gpt-5.1","output":[]}}""",
      """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}""",
      """{"type":"response.reasoning_summary_text.delta","sequence_number":2,"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Thinking"}""",
      """{"type":"response.output_item.done","sequence_number":3,"output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Thinking"}],"encrypted_content":"gAAAA"}}""",
      """{"type":"response.output_item.added","sequence_number":4,"output_index":1,"item":{"type":"message","id":"msg_1","role":"assistant","content":[],"status":"in_progress"}}""",
      """{"type":"response.content_part.added","sequence_number":5,"item_id":"msg_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"","annotations":[]}}""",
      """{"type":"response.output_text.delta","sequence_number":6,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"Hi "}""",
      """{"type":"response.output_text.delta","sequence_number":7,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"there"}""",
      """{"type":"response.content_part.done","sequence_number":8,"item_id":"msg_1","output_index":1,"content_index":0,"part":{"type":"output_text","text":"Hi there","annotations":[]}}""",
      """{"type":"response.output_item.done","sequence_number":9,"output_index":1,"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hi there","annotations":[]}]}}""",
      """{"type":"response.output_item.added","sequence_number":10,"output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_abc","name":"Bash","arguments":"","status":"in_progress"}}""",
      """{"type":"response.function_call_arguments.delta","sequence_number":11,"item_id":"fc_1","output_index":2,"delta":"{\"command\":"}""",
      """{"type":"response.function_call_arguments.delta","sequence_number":12,"item_id":"fc_1","output_index":2,"delta":"\"ls\"}"}""",
      """{"type":"response.output_item.done","sequence_number":13,"output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_abc","name":"Bash","arguments":"{\"command\":\"ls\"}","status":"completed"}}""",
      """{"type":"response.completed","sequence_number":14,"response":{"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt-5.1","output":[{"type":"function_call","id":"fc_1","call_id":"call_abc","name":"Bash","arguments":"{}","status":"completed"}],"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":10},"total_tokens":130}}}"""
    ).map(JsonCodec[oa.ResponseStreamEvent].fromJson)
    val dec   = oa.ResponsesConverter.StreamDecoder()
    val parts = events.flatMap(dec.decode) ++ dec.finish()
    val m     = toAnthropic(parts)
    m.id shouldBe "resp_1"
    m.model shouldBe "gpt-5.1"
    m.content.size shouldBe 3
    m.content(0) shouldMatch { case an.ContentBlock.Thinking("Thinking", sig) =>
      // The encrypted reasoning comes back from Claude Code in the signature
      LlmConvert.Carrier.decode(sig).map(_._1) shouldBe Some(ProviderMetadata.OpenAI)
    }
    m.content(1) shouldBe an.ContentBlock.Text("Hi there")
    m.content(2) shouldBe
      an.ContentBlock.ToolUse("call_abc", "Bash", JSON.parseAny("""{"command":"ls"}"""))
    m.stopReason shouldBe Some(an.StopReason.ToolUse)
    // Anthropic input tokens exclude cache reads
    m.usage.inputTokens shouldBe 60
    m.usage.cacheReadInputTokens shouldBe Some(40L)
    m.usage.outputTokens shouldBe 30

    // On the next turn, the carried reasoning goes back to OpenAI unchanged
    val history = an.MessagesRequest(
      "claude",
      100,
      Seq(an.Message.user("q"), an.Message(an.Role.Assistant, m.content))
    )
    val next = oa.ResponsesConverter.fromLlmRequest(an.AnthropicConverter.toLlmRequest(history))
    next
      .input
      .collectFirst { case r: oa.Item.Reasoning =>
        r
      } shouldBe
      Some(
        oa.Item
          .Reasoning("rs_1", Seq(oa.ReasoningText("Thinking")), encryptedContent = Some("gAAAA"))
      )
  }

  test("Chat Completions stream to Anthropic events, with usage after the finish chunk") {
    val chunks = Seq(
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"hmm"},"finish_reason":null}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Bash","arguments":""}}]},"finish_reason":null}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"command\":\"ls\"}"}}]},"finish_reason":null}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"qwen3","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":12,"total_tokens":62}}"""
    ).map(JsonCodec[oa.ChatCompletionChunk].fromJson)
    val dec   = oa.ChatCompletionsConverter.StreamDecoder()
    val parts = chunks.flatMap(dec.decode) ++ dec.finish()
    val m     = toAnthropic(parts)
    m.content.map(_.blockType) shouldBe Seq("thinking", "text", "tool_use")
    m.content(1) shouldBe an.ContentBlock.Text("Hello")
    m.stopReason shouldBe Some(an.StopReason.ToolUse)
    m.usage.inputTokens shouldBe 50
    m.usage.outputTokens shouldBe 12
  }

  test("Gemini stream to Anthropic events; the thought signature comes back on the next turn") {
    val chunks = Seq(
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"Plan","thought":true}]}}],"modelVersion":"gemini-3-pro","responseId":"g1"}""",
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"Sure"}]}}]}""",
      """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"Bash","args":{"command":"ls"}},"thoughtSignature":"R2VtaW5p"}]},"finishReason":"STOP"}],
        |"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":8,"thoughtsTokenCount":4,"totalTokenCount":32}}"""
        .stripMargin
    ).map(JsonCodec[gm.GenerateContentResponse].fromJson)
    val dec   = gm.GeminiConverter.StreamDecoder()
    val parts = chunks.flatMap(dec.decode) ++ dec.finish()
    val m     = toAnthropic(parts)
    m.model shouldBe "gemini-3-pro"
    // thinking (unsigned), text, carrier thinking, tool_use
    m.content.map(_.blockType) shouldBe Seq("thinking", "text", "thinking", "tool_use")
    m.stopReason shouldBe Some(an.StopReason.ToolUse)
    m.usage.outputTokens shouldBe 12

    val history = an.MessagesRequest(
      "claude",
      100,
      Seq(
        an.Message.user("q"),
        an.Message(an.Role.Assistant, m.content),
        an.Message(
          an.Role.User,
          Seq(an.ContentBlock.ToolResult(m.toolUses.head.id, Seq(an.ContentBlock.Text("ok"))))
        )
      )
    )
    val next = gm.GeminiConverter.fromLlmRequest(an.AnthropicConverter.toLlmRequest(history))
    val call =
      next
        .contents(1)
        .parts
        .collectFirst { case f: gm.Part.FunctionCall =>
          f
        }
        .get
    call.thoughtSignature shouldBe Some("R2VtaW5p")
    next.contents(2).parts.head shouldMatch { case gm.Part.FunctionResponse("Bash", _, _, _) =>
    }
  }

  private def anthropicParts: Seq[LlmStreamPart] =
    val events = Seq(
      """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":7,"output_tokens":1,"cache_read_input_tokens":3}}}""",
      """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
      """{"type":"content_block_stop","index":0}""",
      """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"Read","input":{}}}""",
      """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a\"}"}}""",
      """{"type":"content_block_stop","index":1}""",
      """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}""",
      """{"type":"message_stop"}"""
    ).map(JsonCodec[an.StreamEvent].fromJson)
    val dec = an.AnthropicConverter.StreamDecoder()
    events.flatMap(dec.decode) ++ dec.finish()

  private def neutral(parts: Seq[LlmStreamPart]): LlmResponse =
    val acc = LlmStreamAccumulator()
    parts.foreach(acc.add)
    acc.result

  test("Anthropic stream to OpenAI Responses events and back") {
    val enc    = oa.ResponsesConverter.StreamEncoder()
    val events = anthropicParts.flatMap(enc.encode) ++ enc.finish()
    events.head.eventType shouldBe "response.created"
    events.last.eventType shouldBe "response.completed"
    events.map(_.sequenceNumber) shouldBe events.indices.map(_.toLong)
    val wire = events.map(e => oa.ResponseStreamEvent.fromServerSentEvent(e.toServerSentEvent))
    val dec  = oa.ResponsesConverter.StreamDecoder()
    val r    = neutral(wire.flatMap(dec.decode) ++ dec.finish())
    r.text shouldBe "Hello"
    r.toolCalls shouldMatch { case Seq(LlmPart.ToolCall("toolu_9", "Read", input, false, _)) =>
      input shouldBe JSON.parseAny("""{"path":"a"}""")
    }
    r.finishReason.kind shouldBe FinishReason.Kind.ToolCalls
    r.usage shouldBe LlmUsage(inputTokens = 10, outputTokens = 20, cacheReadTokens = 3)
  }

  test("Anthropic stream to Chat Completions chunks and back") {
    val enc    = oa.ChatCompletionsConverter.StreamEncoder()
    val chunks = anthropicParts.flatMap(enc.encode) ++ enc.finish()
    chunks.last.choices shouldBe Nil
    chunks.last.usage.map(_.promptTokens) shouldBe Some(10L)
    val dec = oa.ChatCompletionsConverter.StreamDecoder()
    val r   = neutral(
      chunks
        .map(c => oa.ChatCompletionChunk.fromServerSentEvent(c.toServerSentEvent))
        .flatMap(dec.decode) ++ dec.finish()
    )
    r.text shouldBe "Hello"
    r.toolCalls.map(_.name) shouldBe Seq("Read")
    r.finishReason.kind shouldBe FinishReason.Kind.ToolCalls
  }

  test("Anthropic stream to Gemini chunks") {
    val genc = gm.GeminiConverter.StreamEncoder()
    val gdec = gm.GeminiConverter.StreamDecoder()
    val g    = neutral(
      (anthropicParts.flatMap(genc.encode) ++ genc.finish()).flatMap(gdec.decode) ++ gdec.finish()
    )
    g.text shouldBe "Hello"
    g.toolCalls.map(_.name) shouldBe Seq("Read")
    g.finishReason.kind shouldBe FinishReason.Kind.ToolCalls

  }

  test("non-streaming responses convert between vendors") {
    val res = JsonCodec[oa.ChatCompletion].fromJson(
      """{"id":"c1","object":"chat.completion","created":1,"model":"gpt-4.1",
        |"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{\"x\":1}"}}]},"finish_reason":"tool_calls"}],
        |"usage":{"prompt_tokens":9,"completion_tokens":4,"total_tokens":13,"prompt_tokens_details":{"cached_tokens":2}}}"""
        .stripMargin
    )
    val llm = oa.ChatCompletionsConverter.toLlmResponse(res)
    val a   = an.AnthropicConverter.fromLlmResponse(llm)
    a.content shouldBe Seq(an.ContentBlock.ToolUse("call_1", "f", JSON.parseAny("""{"x":1}""")))
    a.stopReason shouldBe Some(an.StopReason.ToolUse)
    a.usage.inputTokens shouldBe 7

    val g = gm.GeminiConverter.fromLlmResponse(llm)
    g.functionCalls.map(_.name) shouldBe Seq("f")
    gm.GeminiConverter.toLlmResponse(g).finishReason.kind shouldBe FinishReason.Kind.ToolCalls

    val r = oa.ResponsesConverter.fromLlmResponse(llm)
    r.functionCalls.map(_.callId) shouldBe Seq("call_1")
    oa.ResponsesConverter.toLlmResponse(r).finishReason.kind shouldBe FinishReason.Kind.ToolCalls
  }

  test("finish reasons map across vendors") {
    an.AnthropicConverter.toFinishReason(Some("max_tokens")).kind shouldBe FinishReason.Kind.Length
    an.AnthropicConverter.toFinishReason(Some("refusal")).kind shouldBe
      FinishReason.Kind.ContentFilter
    an.AnthropicConverter
      .fromFinishReason(FinishReason(FinishReason.Kind.Stop, Some("stop"))) shouldBe "end_turn"
    an.AnthropicConverter
      .fromFinishReason(FinishReason(FinishReason.Kind.Stop, Some("pause_turn"))) shouldBe
      "pause_turn"
    gm.GeminiConverter.toFinishReason(Some("SAFETY"), false).kind shouldBe
      FinishReason.Kind.ContentFilter
    gm.GeminiConverter.toFinishReason(Some("STOP"), true).kind shouldBe FinishReason.Kind.ToolCalls
    gm.GeminiConverter.toFinishReason(None, false, Some("PROHIBITED_CONTENT")).kind shouldBe
      FinishReason.Kind.ContentFilter
    oa.ChatCompletionsConverter.toFinishReason(Some("stop"), hasToolCalls = true).kind shouldBe
      FinishReason.Kind.ToolCalls
  }

  test("tool ids are rewritten deterministically for vendors that restrict them") {
    LlmConvert.anthropicToolId("call_abc-1") shouldBe "call_abc-1"
    val rewritten = LlmConvert.anthropicToolId("fc|a.b")
    rewritten shouldBe LlmConvert.anthropicToolId("fc|a.b")
    rewritten.forall(c => c.isLetterOrDigit || c == '_' || c == '-') shouldBe true
    (LlmConvert.anthropicToolId("a|b") != LlmConvert.anthropicToolId("a/b")) shouldBe true
    (LlmConvert.anthropicToolId("x" * 200).length <= 128) shouldBe true
  }

end LlmConverterTest
