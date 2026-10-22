package wvlet.uni.weaver

import wvlet.uni.json.JSON
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.test.UniTest

case class OmitCacheControl(`type`: String, ttl: Option[String] = None) derives Weaver

case class OmitTextBlock(
    `type`: String,
    text: String,
    cache_control: Option[OmitCacheControl] = None
) derives Weaver

case class OmitRequest(
    model: String,
    max_tokens: Option[Int] = None,
    system: Option[String] = None,
    blocks: List[OmitTextBlock] = Nil,
    metadata: Map[String, Any] = Map.empty
) derives Weaver

sealed trait OmitToolChoice derives Weaver
case class OmitToolChoiceTool(name: Option[String] = None, disable: Option[Boolean] = None)
    extends OmitToolChoice derives Weaver

class OmitNoneFieldsTest extends UniTest:
  private val omitNone = WeaverConfig().withOmitNoneFields(true)

  private val request = OmitRequest(
    model = "m1",
    blocks = List(
      OmitTextBlock("text", "hello"),
      OmitTextBlock("text", "cached", Some(OmitCacheControl("ephemeral")))
    )
  )

  test("omitNoneFields is disabled by default") {
    WeaverConfig().omitNoneFields shouldBe false
    Weaver.toJson(OmitTextBlock("text", "hello")) shouldBe
      """{"type":"text","text":"hello","cache_control":null}"""
  }

  test("omit None fields in JSON, including nested case classes") {
    val json = Weaver.toJson(request, omitNone)
    json shouldBe
      """{"model":"m1","blocks":[{"type":"text","text":"hello"},{"type":"text","text":"cached","cache_control":{"type":"ephemeral"}}],"metadata":{}}"""

    JSON.parse(json) match
      case obj: JSON.JSONObject =>
        obj.get("max_tokens") shouldBe None
        obj.get("system") shouldBe None
      case other =>
        fail(s"Expected a JSON object, but got ${other}")

    Weaver.fromJson[OmitRequest](json) shouldBe request
  }

  test("omit None fields in MessagePack with a map header counting only written fields") {
    val msgpack = Weaver.weave(request, omitNone)
    MessagePack.newUnpacker(msgpack).unpackValue match
      case Value.MapValue(entries) =>
        entries.keys.map(_.toString).toSet shouldBe Set("model", "blocks", "metadata")
      case other =>
        fail(s"Expected a map, but got ${other}")

    Weaver.unweave[OmitRequest](msgpack) shouldBe request
  }

  test("keep Some values and round-trip a fully populated object") {
    val full = OmitRequest(
      model = "m2",
      max_tokens = Some(100),
      system = Some("be brief"),
      blocks = List(OmitTextBlock("text", "x", Some(OmitCacheControl("ephemeral", Some("1h")))))
    )
    val json = Weaver.toJson(full, omitNone)
    json shouldBe Weaver.toJson(full)
    Weaver.fromJson[OmitRequest](json) shouldBe full
    Weaver.unweave[OmitRequest](Weaver.weave(full, omitNone)) shouldBe full
  }

  test("write an empty map when every field is None") {
    Weaver.toJson(OmitCacheControl("ephemeral"), omitNone) shouldBe """{"type":"ephemeral"}"""
    Weaver.toJson(OmitToolChoiceTool(), omitNone) shouldBe "{}"
  }

  test("omit None fields in sealed trait children") {
    val choice: OmitToolChoice = OmitToolChoiceTool(name = Some("calc"))
    val json                   = Weaver.toJson(choice, omitNone)
    json shouldBe """{"@type":"OmitToolChoiceTool","name":"calc"}"""
    Weaver.fromJson[OmitToolChoice](json) shouldBe choice
  }

  test("keep explicit nulls inside Map[String, Any]") {
    val r    = OmitRequest(model = "m3", metadata = Map("explicit_null" -> null))
    val json = Weaver.toJson(r, omitNone)
    json shouldBe """{"model":"m3","blocks":[],"metadata":{"explicit_null":null}}"""
  }

end OmitNoneFieldsTest
