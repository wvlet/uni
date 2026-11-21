package wvlet.uni.ai.openai

import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.TestJson
import wvlet.uni.http.ServerSentEventParser
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest

/**
  * Decode payloads shaped like the documented examples, and check that encoding them again gives
  * the same JSON, unknown fields and block types included.
  */
class OpenAIModelTest extends UniTest:

  private def sameJson(a: String, b: String): Unit = TestJson.parse(a) shouldBe TestJson.parse(b)

  private def roundTrip[A](json: String)(using c: JsonCodec[A]): A =
    val a = c.fromJson(json)
    sameJson(c.toJson(a), json)
    a

  test("OpenAI Responses request and response round-trip") {
    val req = roundTrip[ResponsesRequest]("""{"model":"gpt-5.1","input":[
        | {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"},{"type":"input_image","image_url":"https://example.com/a.png","detail":"auto"}]},
        | {"type":"function_call","id":"fc_1","call_id":"call_1","name":"f","arguments":"{\"a\":1}"},
        | {"type":"function_call_output","call_id":"call_1","output":"ok"},
        | {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thought"}],"encrypted_content":"gAAA"},
        | {"type":"item_reference","id":"msg_9"}
        |],
        |"tools":[{"type":"function","name":"f","parameters":{"type":"object"},"strict":true},{"type":"web_search"}],
        |"reasoning":{"effort":"medium","summary":"auto"},"store":false,"include":["reasoning.encrypted_content"],
        |"text":{"verbosity":"low"}}""".stripMargin)
    req.input.last shouldMatch { case Item.Unknown(_) =>
    }
    req.extra.keySet shouldBe Set("text")

    val res = roundTrip[Response]("""{"id":"resp_1","object":"response","created_at":1700000000,"status":"completed","model":"gpt-5.1",
        |"output":[
        | {"type":"reasoning","id":"rs_1","summary":[]},
        | {"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello","annotations":[]}]}
        |],
        |"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4},"output_tokens":20,"output_tokens_details":{"reasoning_tokens":12},"total_tokens":30},
        |"incomplete_details":null,"error":null,"instructions":null,"tools":[]}""".stripMargin)
    res.outputText shouldBe "Hello"
    res.usage.flatMap(_.cachedTokens) shouldBe Some(4L)
  }

  test("OpenAI Responses input given as a string decodes to a user message") {
    val r = JsonCodec[ResponsesRequest].fromJson("""{"model":"m","input":"hello"}""")
    r.input shouldBe Seq(Item.user("hello"))
  }

  test("OpenAI Responses stream events round-trip") {
    val lines = Seq(
      """{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","object":"response","created_at":1,"status":"in_progress","model":"gpt-5.1","output":[],"incomplete_details":null,"error":null}}""",
      """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"f","arguments":"","status":"in_progress"}}""",
      """{"type":"response.function_call_arguments.delta","sequence_number":2,"item_id":"fc_1","output_index":0,"delta":"{\"a\":"}""",
      """{"type":"response.function_call_arguments.done","sequence_number":3,"item_id":"fc_1","output_index":0,"arguments":"{\"a\":1}"}""",
      """{"type":"response.output_text.delta","sequence_number":4,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"Hi"}""",
      """{"type":"response.reasoning_summary_text.delta","sequence_number":5,"item_id":"rs_1","output_index":2,"summary_index":1,"delta":"x"}""",
      """{"type":"response.web_search_call.searching","sequence_number":6,"output_index":3,"item_id":"ws_1"}""",
      """{"type":"error","sequence_number":7,"code":"server_error","message":"boom","param":null}"""
    )
    val events = lines.map(JsonCodec[ResponseStreamEvent].fromJson)
    events(2) shouldBe
      ResponseStreamEvent.TextDelta(
        ResponseStreamEvent.FunctionArgumentsDelta,
        "fc_1",
        0,
        0,
        "{\"a\":",
        2
      )
    events(6) shouldMatch { case _: ResponseStreamEvent.Unknown =>
    }
    events
      .zip(lines)
      .take(6)
      .foreach { (e, l) =>
        sameJson(JsonCodec[ResponseStreamEvent].toJson(e), l)
      }
  }

  test("Chat Completions request, response, and chunks round-trip") {
    val req = roundTrip[ChatCompletionRequest](
      """{"model":"gpt-4.1","messages":[
        | {"role":"system","content":"be brief"},
        | {"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBO"}}]},
        | {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
        | {"role":"tool","tool_call_id":"call_1","content":"42"}
        |],
        |"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],
        |"stream":true,"stream_options":{"include_usage":true},"response_format":{"type":"text"}}"""
        .stripMargin
    )
    req.includeUsage shouldBe Some(true)
    req.extra.keySet shouldBe Set("response_format")

    roundTrip[ChatCompletion]("""{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4.1",
        |"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],
        |"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7,"prompt_tokens_details":{"cached_tokens":0}},
        |"system_fingerprint":"fp_1"}""".stripMargin).text shouldBe "Hi"

    val chunk = JsonCodec[ChatCompletionChunk].fromJson(
      """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\""}}]},"finish_reason":null}]}"""
    )
    chunk.choices.head.delta.toolCalls.head shouldBe
      ChatToolCallDelta(0, arguments = Some("{\"a\""))
  }

end OpenAIModelTest
