package wvlet.uni.ai.gemini

import wvlet.uni.ai.JsonCodec
import wvlet.uni.ai.TestJson
import wvlet.uni.http.ServerSentEventParser
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest

/**
  * Decode payloads shaped like the documented examples, and check that encoding them again gives
  * the same JSON, unknown fields and block types included.
  */
class GeminiModelTest extends UniTest:

  private def sameJson(a: String, b: String): Unit = TestJson.parse(a) shouldBe TestJson.parse(b)

  private def roundTrip[A](json: String)(using c: JsonCodec[A]): A =
    val a = c.fromJson(json)
    sameJson(c.toJson(a), json)
    a

  test("Gemini response with a signed function call round-trips and accumulates") {
    val json =
      """{"candidates":[{"content":{"role":"model","parts":[
        | {"text":"Checking","thought":true},
        | {"functionCall":{"id":"fc-1","name":"get_weather","args":{"city":"Tokyo"}},"thoughtSignature":"c2ln"},
        | {"executableCode":{"language":"PYTHON","code":"print(1)"}}
        |]},"finishReason":"STOP","index":0,"safetyRatings":[]}],
        |"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":25,"thoughtsTokenCount":10},
        |"modelVersion":"gemini-2.5-flash","responseId":"r1"}""".stripMargin
    val r = roundTrip[GenerateContentResponse](json)
    r.functionCalls.head.thoughtSignature shouldBe Some("c2ln")
    r.text shouldBe ""

    val acc = GenerateContentAccumulator()
    acc.add(
      JsonCodec[GenerateContentResponse].fromJson(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}"""
      )
    )
    acc.add(
      JsonCodec[GenerateContentResponse].fromJson(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2,"totalTokenCount":3}}"""
      )
    )
    acc.result.text shouldBe "Hello"
    acc.result.candidates.head.finishReason shouldBe Some("STOP")
  }

  test("Gemini request with tools and thinking config round-trips") {
    roundTrip[GenerateContentRequest]("""{"contents":[{"role":"user","parts":[{"text":"hi"},{"inlineData":{"mimeType":"image/png","data":"iVBO"}}]}],
        |"systemInstruction":{"parts":[{"text":"sys"}]},
        |"tools":[{"functionDeclarations":[{"name":"f","parametersJsonSchema":{"type":"object"}}]},{"googleSearch":{}}],
        |"toolConfig":{"functionCallingConfig":{"mode":"AUTO"}},
        |"generationConfig":{"maxOutputTokens":100,"thinkingConfig":{"thinkingBudget":512,"includeThoughts":true},"responseMimeType":"text/plain"},
        |"safetySettings":[]}""".stripMargin).tools(1).extra.keySet shouldBe Set("googleSearch")
  }

end GeminiModelTest
