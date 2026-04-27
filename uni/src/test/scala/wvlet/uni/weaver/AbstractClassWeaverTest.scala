package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object AbstractClassWeaverTest:

  // Non-sealed abstract class with concrete case-class subclasses
  abstract class Animal(val name: String)
  case class Dog(override val name: String, breed: String) extends Animal(name) derives Weaver
  case class Cat(override val name: String, color: String) extends Animal(name) derives Weaver

  given Weaver[Animal] = Weaver.subclassesOf[Animal](
    classOf[Dog] -> Weaver.of[Dog],
    classOf[Cat] -> Weaver.of[Cat]
  )

  // Owner can now derive because Weaver[Animal] is in scope
  case class Owner(name: String, pet: Animal) derives Weaver

  // Cover the Seq[(Class, Weaver)] overload (programmatic construction path)
  abstract class Shape
  case class Circle(radius: Double) extends Shape derives Weaver
  case class Square(side: Double)   extends Shape derives Weaver

  val shapeChildren: Seq[(Class[? <: Shape], Weaver[? <: Shape])] = Seq(
    classOf[Circle] -> Weaver.of[Circle],
    classOf[Square] -> Weaver.of[Square]
  )

  given Weaver[Shape] = Weaver.subclassesOf[Shape](shapeChildren*)

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
      Weaver.subclassesOf[Animal]()
    }
    e.getMessage shouldContain "at least one"
  }

  test("error: subclassesOf rejects duplicate subclass simpleNames") {
    val e = intercept[IllegalArgumentException] {
      Weaver.subclassesOf[Animal](classOf[Dog] -> Weaver.of[Dog], classOf[Dog] -> Weaver.of[Dog])
    }
    e.getMessage shouldContain "duplicate subclass names"
  }

  test("Seq overload also round-trips") {
    val s: Shape = Circle(2.5)
    val json     = Weaver.toJson(s)
    val restored = Weaver.fromJson[Shape](json)
    restored shouldBe s
  }

end AbstractClassWeaverTest
