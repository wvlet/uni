package wvlet.uni.weaver

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

import java.time.LocalTime

case class TimedEvent(name: String, at: LocalTime, until: Option[LocalTime]) derives Weaver

class LocalTimeWeaverTest extends UniTest:
  private val time = LocalTime.of(9, 30, 15)

  test("pack LocalTime as an ISO-8601 string") {
    summon[Weaver[LocalTime]].toJson(time) shouldBe "\"09:30:15\""
    summon[Weaver[LocalTime]].fromJson("\"09:30:15\"") shouldBe time
    summon[Weaver[LocalTime]].fromJson("\"09:30\"") shouldBe LocalTime.of(9, 30)
    val precise = LocalTime.of(23, 59, 59, 123456789)
    summon[Weaver[LocalTime]].fromJson(summon[Weaver[LocalTime]].toJson(precise)) shouldBe precise
  }

  test("round-trip a case class with LocalTime fields") {
    val e    = TimedEvent("standup", time, Some(LocalTime.of(9, 45)))
    val json = Weaver.of[TimedEvent].toJson(e)
    json shouldBe """{"name":"standup","at":"09:30:15","until":"09:45"}"""
    Weaver.of[TimedEvent].fromJson(json) shouldBe e
    Weaver.of[TimedEvent].unweave(Weaver.of[TimedEvent].weave(e)) shouldBe e
  }

  test("build the same weaver from a Surface") {
    val w = Weaver.fromSurfaceOpt(Surface.of[TimedEvent])
    w.isDefined shouldBe true
    val e = TimedEvent("x", time, None)
    w.get.asInstanceOf[Weaver[TimedEvent]].unweave(Weaver.of[TimedEvent].weave(e)) shouldBe e
  }

  test("unpack an integer as nanoseconds of the day") {
    val packer = MessagePack.newPacker()
    packer.packLong(time.toNanoOfDay)
    summon[Weaver[LocalTime]].unweave(packer.toByteArray) shouldBe time
  }

  test("report a malformed time as an error") {
    val e = intercept[Exception] {
      summon[Weaver[LocalTime]].fromJson("\"25:61\"")
    }
    e.getMessage shouldContain "LocalTime"
  }

end LocalTimeWeaverTest
