package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object AbstractClassWeaverTest:

  // Non-sealed abstract class with concrete case-class subclasses
  abstract class Animal(val name: String)
  case class Dog(override val name: String, breed: String) extends Animal(name) derives Weaver
  case class Cat(override val name: String, color: String) extends Animal(name) derives Weaver

  given Weaver[Animal] = Weaver.subclassesOf[Animal](
    Weaver.SubclassEntry(classOf[Dog], Weaver.of[Dog]),
    Weaver.SubclassEntry(classOf[Cat], Weaver.of[Cat])
  )

  // Owner can now derive because Weaver[Animal] is in scope
  case class Owner(name: String, pet: Animal) derives Weaver

  // Cover the programmatic-construction path: build a Seq of entries and splat it
  abstract class Shape
  case class Circle(radius: Double) extends Shape derives Weaver
  case class Square(side: Double)   extends Shape derives Weaver

  val shapeChildren: Seq[Weaver.SubclassEntry[Shape, ?]] = Seq(
    Weaver.SubclassEntry(classOf[Circle], Weaver.of[Circle]),
    Weaver.SubclassEntry(classOf[Square], Weaver.of[Square])
  )

  given Weaver[Shape] = Weaver.subclassesOf[Shape](shapeChildren*)

  // Non-sealed trait with case-object children. Uses explicit `Some(...)` for the singleton so
  // the registration is portable to JVM, Scala.js, and Native.
  trait Signal
  case object On                    extends Signal
  case object Off                   extends Signal
  case class Pulse(durationMs: Int) extends Signal derives Weaver
  given Weaver[Signal] = Weaver.subclassesOf[Signal](
    Weaver.SubclassEntry(classOf[On.type], Weaver.of[On.type], Some(On)),
    Weaver.SubclassEntry(classOf[Off.type], Weaver.of[Off.type], Some(Off)),
    Weaver.SubclassEntry(classOf[Pulse], Weaver.of[Pulse])
  )

end AbstractClassWeaverTest

class AbstractClassWeaverTest extends UniTest:
  import AbstractClassWeaverTest.*

  test("round-trip Owner with Dog through MsgPack") {
    val owner    = Owner("Alice", Dog("Rex", "Labrador"))
    val msgpack  = Weaver.weave(owner)
    val restored = Weaver.unweave[Owner](msgpack)
    restored shouldBe owner
  }

  test("round-trip Owner with Cat through JSON") {
    val owner = Owner("Bob", Cat("Whiskers", "tabby"))
    val json  = Weaver.toJson(owner)
    json shouldContain "\"@type\":\"Cat\""
    json shouldContain "\"color\":\"tabby\""
    val restored = Weaver.fromJson[Owner](json)
    restored shouldBe owner
  }

  test("Animal directly: round-trip top-level abstract type") {
    val pet: Animal = Dog("Spot", "Beagle")
    val json        = Weaver.toJson(pet)
    json shouldContain "\"@type\":\"Dog\""
    val restored = Weaver.fromJson[Animal](json)
    restored shouldBe pet
  }

  test("custom discriminator field name carries through") {
    val cfg    = WeaverConfig(discriminatorFieldName = "type")
    val owner  = Owner("Carol", Dog("Rex", "Poodle"))
    val weaver = summon[Weaver[Owner]]
    val json   = weaver.toJson(owner, cfg)
    json shouldContain "\"type\":\"Dog\""
    val restored = weaver.fromJson(json, cfg)
    restored shouldBe owner
  }

  test("error: pack instance whose class wasn't registered") {
    case class Fish(override val name: String, depth: Int) extends Animal(name) derives Weaver
    val rogue: Animal = Fish("Nemo", 50)
    val e             = intercept[IllegalArgumentException] {
      Weaver.weave(rogue)
    }
    e.getMessage shouldContain "Unknown child type 'Fish'"
  }

  test("error: unpack JSON with unknown @type") {
    val json = """{"@type":"Bird","name":"Tweety"}"""
    val e    = intercept[IllegalArgumentException] {
      Weaver.fromJson[Animal](json)
    }
    e.getMessage shouldContain "Unknown type 'Bird'"
  }

  test("error: subclassesOf rejects empty list") {
    val e = intercept[IllegalArgumentException] {
      Weaver.subclassesOf[Animal](Seq.empty[Weaver.SubclassEntry[Animal, ?]]*)
    }
    e.getMessage shouldContain "at least one"
  }

  test("error: subclassesOf rejects names that collide after canonicalization") {
    // FooBar and foo_bar canonicalize to the same discriminator
    abstract class Marker
    case class FooBar(value: Int)  extends Marker derives Weaver
    case class foo_bar(value: Int) extends Marker derives Weaver
    val e = intercept[IllegalArgumentException] {
      Weaver.subclassesOf[Marker](
        Weaver.SubclassEntry(classOf[FooBar], Weaver.of[FooBar]),
        Weaver.SubclassEntry(classOf[foo_bar], Weaver.of[foo_bar])
      )
    }
    e.getMessage shouldContain "collide after canonicalization"
  }

  test("Seq splat overload also round-trips") {
    val s: Shape = Circle(2.5)
    val json     = Weaver.toJson(s)
    val restored = Weaver.fromJson[Shape](json)
    restored shouldBe s
  }

  test("case-object subclass round-trips via explicit singleton (cross-platform)") {
    val on: Signal = On
    val onJson     = Weaver.toJson(on)
    onJson shouldBe """{"@type":"On"}"""
    Weaver.fromJson[Signal](onJson) shouldBe On

    val off: Signal = Off
    Weaver.fromJson[Signal](Weaver.toJson(off)) shouldBe Off
  }

  test("error-message names the parent type from ClassTag, not a child's superclass") {
    val e = intercept[IllegalArgumentException] {
      Weaver.fromJson[Animal]("""{"@type":"Bird"}""")
    }
    e.getMessage shouldContain "Animal"
  }

  test("case-class child of a trait still round-trips alongside case objects") {
    val pulse: Signal = Pulse(250)
    val json          = Weaver.toJson(pulse)
    json shouldContain "\"@type\":\"Pulse\""
    json shouldContain "\"durationMs\":250"
    Weaver.fromJson[Signal](json) shouldBe pulse
  }

  test("SubclassEntry.singleton works for plain (non-case) object subclasses") {
    trait Marker
    object Alpha extends Marker
    object Beta  extends Marker

    given Weaver[Marker] = Weaver.subclassesOf[Marker](
      Weaver.SubclassEntry.singleton[Marker, Alpha.type](classOf[Alpha.type], Alpha),
      Weaver.SubclassEntry.singleton[Marker, Beta.type](classOf[Beta.type], Beta)
    )

    val a: Marker = Alpha
    Weaver.fromJson[Marker](Weaver.toJson(a)) shouldBe Alpha

    val b: Marker = Beta
    Weaver.fromJson[Marker](Weaver.toJson(b)) shouldBe Beta
  }

end AbstractClassWeaverTest
