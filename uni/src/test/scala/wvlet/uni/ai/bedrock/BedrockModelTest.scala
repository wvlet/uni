package wvlet.uni.ai.bedrock

import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.TestJson
import wvlet.uni.http.ServerSentEventParser
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest

/**
  * Decode payloads shaped like the documented examples, and check that encoding them again gives
  * the same JSON, unknown fields and block types included.
  */
class BedrockModelTest extends UniTest:

  private def sameJson(a: String, b: String): Unit = TestJson.parse(a) shouldBe TestJson.parse(b)

  private def roundTrip[A](json: String)(using c: JsonCodec[A]): A =
    val a = c.fromJson(json)
    sameJson(c.toJson(a), json)
    a

  test("Bedrock Converse request and response round-trip") {
    val req = roundTrip[ConverseRequest]("""{"messages":[
        | {"role":"user","content":[{"text":"hi"},{"image":{"format":"png","source":{"bytes":"iVBO"}}},{"cachePoint":{"type":"default"}}]},
        | {"role":"assistant","content":[{"reasoningContent":{"reasoningText":{"text":"hmm","signature":"sig"}}},{"toolUse":{"toolUseId":"t1","name":"f","input":{"a":1}}}]},
        | {"role":"user","content":[{"toolResult":{"toolUseId":"t1","content":[{"json":{"r":2}}],"status":"success"}}]}
        |],
        |"system":[{"text":"sys"}],
        |"inferenceConfig":{"maxTokens":512,"temperature":0.5},
        |"toolConfig":{"tools":[{"toolSpec":{"name":"f","inputSchema":{"json":{"type":"object"}}}}],"toolChoice":{"auto":{}}},
        |"additionalModelRequestFields":{"thinking":{"type":"enabled","budget_tokens":1024}},
        |"guardrailConfig":{"guardrailIdentifier":"g","guardrailVersion":"1"}}""".stripMargin)
    req.messages.head.content.last shouldBe ContentBlock.CachePoint
    req.extra.keySet shouldBe Set("guardrailConfig")

    roundTrip[ConverseResponse](
      """{"output":{"message":{"role":"assistant","content":[{"text":"Hello"}]}},"stopReason":"end_turn",
        |"usage":{"inputTokens":3,"outputTokens":4,"totalTokens":7},"metrics":{"latencyMs":120}}"""
        .stripMargin
    ).text shouldBe "Hello"
  }

  test("event-stream frames decode across chunk boundaries and check CRCs") {
    val frames = Seq(
      EventStreamFrame.event("messageStart", """{"role":"assistant"}"""),
      EventStreamFrame.event(
        "contentBlockDelta",
        """{"contentBlockIndex":0,"delta":{"text":"Hi"},"p":"abc"}"""
      ),
      EventStreamFrame.event("messageStop", """{"stopReason":"end_turn"}"""),
      EventStreamFrame.event(
        "metadata",
        """{"usage":{"inputTokens":1,"outputTokens":2,"totalTokens":3},"metrics":{"latencyMs":5}}"""
      )
    )
    val all     = frames.flatten.toArray
    val decoder = EventStreamDecoder()
    // Feed in 7-byte chunks
    val decoded = all.grouped(7).flatMap(c => decoder.feed(c)).toSeq
    decoder.finish()
    decoded.size shouldBe 4
    val events = decoded.map(ConverseStreamEvent.fromFrame)
    events(1) shouldBe ConverseStreamEvent.ContentBlockDelta(0, text = Some("Hi"))
    val acc = ConverseAccumulator()
    events.foreach(acc.add)
    acc.result.text shouldBe "Hi"
    acc.result.usage.outputTokens shouldBe 2

    val corrupt = frames.head.clone()
    corrupt(corrupt.length - 6) = (corrupt(corrupt.length - 6) ^ 1).toByte
    intercept[EventStreamException] {
      EventStreamDecoder().feed(corrupt)
    }
  }

end BedrockModelTest
