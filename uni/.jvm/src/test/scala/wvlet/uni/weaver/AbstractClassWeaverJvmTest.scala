package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object AbstractClassWeaverJvmTest:

  // Same shape as the cross-platform Signal test, but uses the JVM-only
  // `SubclassEntry.forSingleton` helper that recovers the singleton via MODULE$ reflection.
  // JS/Native users should construct SubclassEntry with `singleton = Some(...)` instead.
  trait Signal
  case object On                    extends Signal
  case object Off                   extends Signal
  case class Pulse(durationMs: Int) extends Signal derives Weaver

  given Weaver[Signal] = Weaver.subclassesOf[Signal](
    Weaver.SubclassEntry.forSingleton[Signal, On.type](classOf[On.type], Weaver.of[On.type]),
    Weaver.SubclassEntry.forSingleton[Signal, Off.type](classOf[Off.type], Weaver.of[Off.type]),
    Weaver.SubclassEntry(classOf[Pulse], Weaver.of[Pulse])
  )

end AbstractClassWeaverJvmTest

class AbstractClassWeaverJvmTest extends UniTest:
  import AbstractClassWeaverJvmTest.*

  test("MODULE$ reflection via SubclassEntry.forSingleton recovers singleton on JVM") {
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

  test("forSingleton rejects non-module classes with a clear error") {
    case class NotAModule(value: Int) derives Weaver
    val e = intercept[IllegalArgumentException] {
      Weaver.SubclassEntry.forSingleton[Any, NotAModule](classOf[NotAModule], Weaver.of[NotAModule])
    }
    e.getMessage shouldContain "not a Scala module"
  }

end AbstractClassWeaverJvmTest
