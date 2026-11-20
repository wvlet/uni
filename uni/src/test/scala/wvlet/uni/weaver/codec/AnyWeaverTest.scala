package wvlet.uni.weaver.codec

import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

class AnyWeaverTest extends UniTest:

  test("weave Map[String, Any] with primitives") {
    val v: Map[String, Any] = Map("name" -> "Alice", "age" -> 30, "height" -> 5.7, "active" -> true)
    val msgpack             = Weaver.weave(v)
    val v2                  = Weaver.unweave[Map[String, Any]](msgpack)

    // Note: Int becomes Long after roundtrip
    v2("name") shouldBe "Alice"
    v2("age") shouldBe 30L
    v2("height") shouldBe 5.7
    v2("active") shouldBe true
  }

  test("weave Map[String, Any] toJson") {
    val v: Map[String, Any] = Map("key1" -> "value", "key2" -> 42, "key3" -> 3.14)
    val json                = Weaver.toJson(v)
    val v2                  = Weaver.fromJson[Map[String, Any]](json)

    v2("key1") shouldBe "value"
    v2("key2") shouldBe 42L
    v2("key3") shouldBe 3.14
  }

  test("weave nested Map[String, Any]") {
    val v: Map[String, Any] = Map(
      "outer" -> Map("inner" -> "value", "number" -> 100),
      "list"  -> Seq(1, 2, 3)
    )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Any]](msgpack)

    v2("outer").asInstanceOf[Map[Any, Any]]("inner") shouldBe "value"
    v2("outer").asInstanceOf[Map[Any, Any]]("number") shouldBe 100L
    v2("list").asInstanceOf[Seq[Any]] shouldBe Seq(1L, 2L, 3L)
  }

  test("weave Seq[Any]") {
    val v: Seq[Any] = Seq("hello", 42, 3.14, true, null)
    val msgpack     = Weaver.weave(v)
    val v2          = Weaver.unweave[Seq[Any]](msgpack)

    v2(0) shouldBe "hello"
    v2(1) shouldBe 42L
    v2(2) shouldBe 3.14
    v2(3) shouldBe true
    v2(4) shouldBe null
  }

  test("weave null values") {
    val v: Any  = null
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Any](msgpack)
    v2 shouldBe null
  }

  test("weave Option[Any]") {
    val v1: Any  = Some("value")
    val msgpack1 = Weaver.weave(v1)
    val v1r      = Weaver.unweave[Any](msgpack1)
    v1r shouldBe "value" // Option unwrapped during pack

    val v2: Any  = None
    val msgpack2 = Weaver.weave(v2)
    val v2r      = Weaver.unweave[Any](msgpack2)
    v2r shouldBe null
  }

  test("weave Scala 3 enum") {
    enum Color:
      case Red,
        Green,
        Blue

    val v: Any  = Color.Red
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Any](msgpack)
    v2 shouldBe "Red" // Enum serialized as string
  }

  test("weave Array[Any]") {
    val v: Any  = Array(1, 2, 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Any](msgpack)

    v2.asInstanceOf[Seq[Any]] shouldBe Seq(1L, 2L, 3L)
  }

  test("weave Array[Byte] as binary") {
    val v: Any  = Array[Byte](1, 2, 3, 4, 5)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Any](msgpack)

    v2.asInstanceOf[Array[Byte]].toSeq shouldBe Seq[Byte](1, 2, 3, 4, 5)
  }

  test("weave mixed type Map keys") {
    val v: Map[Any, Any] = Map("stringKey" -> 1, 42 -> "intKey", true -> "boolKey")
    val msgpack          = Weaver.weave(v)
    val v2               = Weaver.unweave[Map[Any, Any]](msgpack)

    v2("stringKey") shouldBe 1L
    v2(42L) shouldBe "intKey"
    v2(true) shouldBe "boolKey"
  }

  test("handle unknown types with toString") {
    case class CustomType(value: String)

    val v: Any  = CustomType("test")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Any](msgpack)

    v2 shouldBe "CustomType(test)"
  }

  test("use knownWeavers for custom types") {
    case class Person(name: String, age: Int)

    val personWeaver = Weaver.derived[Person]
    val anyWeaver    = AnyWeaver(knownWeavers = Map(classOf[Person] -> personWeaver))

    val person  = Person("Bob", 25)
    val msgpack = anyWeaver.weave(person)
    val v2      = anyWeaver.unweave(msgpack)

    // Since we don't encode type info, it unpacks as Map
    v2.asInstanceOf[Map[Any, Any]]("name") shouldBe "Bob"
    v2.asInstanceOf[Map[Any, Any]]("age") shouldBe 25L
  }

  test("use knownWeavers in Map[String, Any]") {
    case class Address(city: String, zip: Int)

    val addressWeaver = Weaver.derived[Address]
    val anyWeaver     = AnyWeaver(knownWeavers = Map(classOf[Address] -> addressWeaver))

    val data: Map[String, Any] = Map("name" -> "Alice", "address" -> Address("NYC", 10001))

    val msgpack = anyWeaver.weave(data)
    val v2      = anyWeaver.unweave(msgpack).asInstanceOf[Map[Any, Any]]

    v2("name") shouldBe "Alice"
    val address = v2("address").asInstanceOf[Map[Any, Any]]
    address("city") shouldBe "NYC"
    address("zip") shouldBe 10001L
  }

  test("weave all primitive types") {
    val v: Map[String, Any] = Map(
      "byte"   -> 1.toByte,
      "short"  -> 2.toShort,
      "int"    -> 3,
      "long"   -> 4L,
      "float"  -> 5.5f,
      "double" -> 6.6,
      "char"   -> 'A',
      "bool"   -> true,
      "string" -> "hello"
    )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Any]](msgpack)

    // All integer types become Long
    v2("byte") shouldBe 1L
    v2("short") shouldBe 2L
    v2("int") shouldBe 3L
    v2("long") shouldBe 4L
    // Float becomes Double
    (v2("float").asInstanceOf[Double] - 5.5).abs < 0.001 shouldBe true
    v2("double") shouldBe 6.6
    // Char becomes String
    v2("char") shouldBe "A"
    v2("bool") shouldBe true
    v2("string") shouldBe "hello"
  }

  test("weave empty collections") {
    val v: Map[String, Any] = Map(
      "emptySeq" -> Seq.empty[Any],
      "emptyMap" -> Map.empty[String, Any]
    )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Any]](msgpack)

    v2("emptySeq").asInstanceOf[Seq[Any]] shouldBe Seq.empty
    v2("emptyMap").asInstanceOf[Map[Any, Any]] shouldBe Map.empty
  }

  test("unweave MessagePack timestamps as Instant") {
    val ts     = java.time.Instant.parse("2026-01-31T12:34:56.123456789Z")
    val packer = wvlet.uni.msgpack.spi.MessagePack.newPacker()
    packer.packMapHeader(1)
    packer.packString("ts")
    packer.packTimestamp(ts)
    val v = Weaver.unweave[Map[String, Any]](packer.toByteArray)
    v("ts") shouldBe ts
  }

end AnyWeaverTest
