package wvlet.uni.weaver

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

import java.time.LocalDate

case class DatedEvent(name: String, dt: LocalDate, due: Option[LocalDate]) derives Weaver

class LocalDateWeaverTest extends UniTest:
  private val day = LocalDate.of(2026, 11, 21)

  test("pack LocalDate as an ISO-8601 string") {
    summon[Weaver[LocalDate]].toJson(day) shouldBe "\"2026-11-21\""
    summon[Weaver[LocalDate]].fromJson("\"2026-11-21\"") shouldBe day
  }

  test("round-trip a case class with LocalDate fields") {
    val e    = DatedEvent("launch", day, Some(day.plusDays(1)))
    val json = Weaver.of[DatedEvent].toJson(e)
    json shouldBe """{"name":"launch","dt":"2026-11-21","due":"2026-11-22"}"""
    Weaver.of[DatedEvent].fromJson(json) shouldBe e
    Weaver.of[DatedEvent].unweave(Weaver.of[DatedEvent].weave(e)) shouldBe e
  }

  test("build the same weaver from a Surface") {
    val w = Weaver.fromSurfaceOpt(Surface.of[DatedEvent])
    w.isDefined shouldBe true
    val e = DatedEvent("x", day, None)
    w.get.asInstanceOf[Weaver[DatedEvent]].unweave(Weaver.of[DatedEvent].weave(e)) shouldBe e
  }

  test("unpack an integer as days since 1970-01-01") {
    val packer = MessagePack.newPacker()
    packer.packLong(day.toEpochDay)
    summon[Weaver[LocalDate]].unweave(packer.toByteArray) shouldBe day
  }

  test("report a malformed date as an error") {
    val e = intercept[Exception] {
      summon[Weaver[LocalDate]].fromJson("\"2026-13-40\"")
    }
    e.getMessage shouldContain "LocalDate"
  }

end LocalDateWeaverTest
