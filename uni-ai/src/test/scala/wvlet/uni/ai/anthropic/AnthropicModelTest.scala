package wvlet.uni.ai.anthropic

import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.TestJson
import wvlet.uni.http.ServerSentEventParser
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest

/**
  * Decode payloads shaped like the documented examples, and check that encoding them again gives
  * the same JSON, unknown fields and block types included.
  */
class AnthropicModelTest extends UniTest:

  private def sameJson(a: String, b: String): Unit = TestJson.parse(a) shouldBe TestJson.parse(b)

  private def roundTrip[A](json: String)(using c: JsonCodec[A]): A =
    val a = c.fromJson(json)
    sameJson(c.toJson(a), json)
    a

  test("Anthropic request with tools, tool results, thinking, and cache_control round-trips") {
    val json =
      """{"model":"claude-sonnet-4-5","max_tokens":4096,
        |"messages":[
        | {"role":"user","content":[{"type":"text","text":"Weather in Tokyo?","cache_control":{"type":"ephemeral"}}]},
        | {"role":"assistant","content":[
        |   {"type":"thinking","thinking":"Need the tool.","signature":"sig123"},
        |   {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"Tokyo"}}]},
        | {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01","content":[{"type":"text","text":"18C"}]}]}
        |],
        |"system":[{"type":"text","text":"Be brief."}],
        |"tools":[
        | {"name":"get_weather","description":"Weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}},
        | {"type":"web_search_20250305","name":"web_search","max_uses":3}
        |],
        |"tool_choice":{"type":"auto"},
        |"thinking":{"type":"enabled","budget_tokens":2048},
        |"stream":true,
        |"context_management":{"edits":[{"type":"clear_tool_uses_20250919"}]}}""".stripMargin
    val r = roundTrip[MessagesRequest](json)
    r.maxTokens shouldBe 4096
    r.tools(1) shouldMatch { case Tool.Unknown(_) =>
    }
    r.extra.keySet shouldBe Set("context_management")
    r.messages(1).content.head shouldBe ContentBlock.Thinking("Need the tool.", "sig123")
  }

  test("Anthropic string content decodes to one text block") {
    val r = JsonCodec[MessagesRequest].fromJson(
      """{"model":"m","max_tokens":10,"system":"sys","messages":[{"role":"user","content":"hi"}]}"""
    )
    r.system shouldBe Seq(ContentBlock.Text("sys"))
    r.messages.head shouldBe Message.user("hi")
  }

  test("Anthropic response keeps unknown blocks such as web_search_tool_result") {
    val json =
      """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
        |"content":[
        | {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"uni"}},
        | {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com","title":"x"}]},
        | {"type":"text","text":"Found it."}
        |],
        |"stop_reason":"end_turn","stop_sequence":null,
        |"usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3,"server_tool_use":{"web_search_requests":1}}}"""
        .stripMargin
    val r = roundTrip[MessagesResponse](json)
    r.content(1).blockType shouldBe "web_search_tool_result"
    r.text shouldBe "Found it."
    r.usage.extra.keySet shouldBe Set("server_tool_use")
  }

  private val anthropicStream =
    """event: message_start
      |data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":25,"output_tokens":1}}}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me check."}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQB"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":0}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}
      |
      |event: ping
      |data: {"type":"ping"}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":1}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"Tokyo\"}"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":2}
      |
      |event: message_delta
      |data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":42}}
      |
      |event: message_stop
      |data: {"type":"message_stop"}
      |
      |""".stripMargin

  private[ai] def anthropicEvents: Seq[StreamEvent] = ServerSentEventParser
    .parse(anthropicStream)
    .map(StreamEvent.fromServerSentEvent)

  test("Anthropic stream events decode, re-encode, and accumulate into a message") {
    val events = anthropicEvents
    events.size shouldBe 15
    events.foreach { e =>
      sameJson(e.toServerSentEvent.data, JsonCodec[StreamEvent].toJson(e))
      e.toServerSentEvent.event shouldBe Some(e.eventType)
    }
    val acc = MessageAccumulator()
    events.foreach(acc.add)
    val m = acc.result
    m.id shouldBe "msg_1"
    m.content shouldBe
      Seq(
        ContentBlock.Thinking("Let me check.", "EqQB"),
        ContentBlock.Text("Hello"),
        ContentBlock.ToolUse("toolu_1", "get_weather", JSON.parseAny("""{"city":"Tokyo"}"""))
      )
    m.stopReason shouldBe Some(StopReason.ToolUse)
    m.usage.inputTokens shouldBe 25
    m.usage.outputTokens shouldBe 42
  }

  test("Anthropic error body decodes") {
    val e = JsonCodec[ErrorResponse].fromJson(
      """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"},"request_id":"req_1"}"""
    )
    e.error.`type` shouldBe ErrorDetail.Overloaded
    ErrorDetail.httpStatusOf(e.error.`type`) shouldBe 529
  }

end AnthropicModelTest
