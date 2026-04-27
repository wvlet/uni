package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object AbstractClassWeaverJvmTest:

  // Same shape as the cross-platform Signal test, but uses the (Class, Weaver) overload
  // which relies on JVM-only MODULE$ reflection to recover the singleton. JS/Native users
  // should use the SubclassEntry overload instead — exercised in the shared test.
  trait Signal
  case object On                    extends Signal
  case object Off                   extends Signal
  case class Pulse(durationMs: Int) extends Signal derives Weaver

  given Weaver[Signal] = Weaver.subclassesOf[Signal](
    classOf[On.type]  -> Weaver.of[On.type],
    classOf[Off.type] -> Weaver.of[Off.type],
    classOf[Pulse]    -> Weaver.of[Pulse]
  )

end AbstractClassWeaverJvmTest

class AbstractClassWeaverJvmTest extends UniTest:
  import AbstractClassWeaverJvmTest.*

  test("MODULE$ reflection recovers singleton on JVM") {
    val on: Signal = On
    Weaver.fromJson[Signal](Weaver.toJson(on)) shouldBe On

    val off: Signal = Off
    Weaver.fromJson[Signal](Weaver.toJson(off)) shouldBe Off
  }

  test("case-class child still round-trips alongside MODULE$-recovered objects") {
    val pulse: Signal = Pulse(250)
    val json          = Weaver.toJson(pulse)
    json shouldContain "\"@type\":\"Pulse\""
    Weaver.fromJson[Signal](json) shouldBe pulse
  }

end AbstractClassWeaverJvmTest
