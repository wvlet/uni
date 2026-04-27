package wvlet.uni.weaver

import wvlet.uni.test.UniTest

object AbstractClassWeaverTest:

  // Non-sealed abstract class. No `given Weaver[Animal]` — the macro's auto-fallback wires the
  // `pet` field to a Surface-driven empty-object weaver (matches airframe-codec's behavior for
  // ObjectMapCodec on a bare abstract Surface: pack as `{}`, unpack as null).
  abstract class Animal(val name: String)
  case class Dog(override val name: String, breed: String) extends Animal(name) derives Weaver
  case class Cat(override val name: String, color: String) extends Animal(name) derives Weaver

  // Compiles even with an open abstract field type. With Option[Animal] the abstract field
  // round-trips as None — the typical adaptation when typed reconstruction isn't required.
  case class Owner(name: String, pet: Option[Animal] = None) derives Weaver

end AbstractClassWeaverTest

class AbstractClassWeaverTest extends UniTest:
  import AbstractClassWeaverTest.*

  test("Weaver.of[Owner] derives even with non-sealed abstract field") {
    val w = summon[Weaver[Owner]]
    w shouldNotBe null
  }

  test("non-abstract fields round-trip; abstract field is lossy (None on read)") {
    val owner = Owner("Alice", Some(Dog("Rex", "Labrador")))
    val json  = Weaver.toJson(owner)
    json shouldContain "\"name\":\"Alice\""
    val restored = Weaver.fromJson[Owner](json)
    restored.name shouldBe "Alice"
    restored.pet shouldBe None // lossy by design — matches airframe ObjectMapCodec
  }

  test("Weaver.fromSurface for abstract Surface returns the empty-object fallback") {
    val w = Weaver.fromSurface(wvlet.uni.surface.Surface.of[Animal])
    w shouldNotBe null
  }

end AbstractClassWeaverTest
