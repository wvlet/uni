package wvlet.uni.weaver

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined
import wvlet.uni.weaver.codec.PrimitiveWeaver.{javaListWeaver, javaMapWeaver, javaSetWeaver}
import scala.jdk.CollectionConverters.*

class WeaverTest extends UniTest:

  test("weave int") {
    val v       = 1
    val msgpack = Weaver.weave(1)
    val v2      = Weaver.unweave[Int](msgpack)
    v shouldBe v2
  }

  test("toJson") {
    val v    = 1
    val json = Weaver.toJson(1)
    val v2   = Weaver.fromJson[Int](json)
    v shouldBe v2
  }

  test("weave string") {
    val v       = "hello"
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[String](msgpack)
    v shouldBe v2
  }

  test("string toJson") {
    val v    = "hello world"
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[String](json)
    v shouldBe v2
  }

  test("weave List[Int]") {
    val v       = List(1, 2, 3, 4, 5)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[List[Int]](msgpack)
    v shouldBe v2
  }

  test("weave empty List[Int]") {
    val v       = List.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[List[Int]](msgpack)
    v shouldBe v2
  }

  test("weave List[String]") {
    val v       = List("hello", "world", "test")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[List[String]](msgpack)
    v shouldBe v2
  }

  test("List[Int] toJson") {
    val v    = List(1, 2, 3)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[List[Int]](json)
    v shouldBe v2
  }

  test("nested List[List[Int]]") {
    val v       = List(List(1, 2), List(3, 4), List(5))
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[List[List[Int]]](msgpack)
    v shouldBe v2
  }

  test("handle malformed array data gracefully") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack array with wrong element count
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(3)    // Say we have 3 elements
    packer.packInt(1)            // Valid first element
    packer.packString("invalid") // Invalid second element for List[Int]
    packer.packInt(3)            // Third element that should be skipped

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[List[Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  test("weave Map[String, Int]") {
    val v       = Map("a" -> 1, "b" -> 2, "c" -> 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Int]](msgpack)
    v shouldBe v2
  }

  test("weave empty Map[String, Int]") {
    val v       = Map.empty[String, Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Int]](msgpack)
    v shouldBe v2
  }

  test("weave Map[Int, String]") {
    val v       = Map(1 -> "one", 2 -> "two", 3 -> "three")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[Int, String]](msgpack)
    v shouldBe v2
  }

  test("Map[String, Int] toJson") {
    val v    = Map("x" -> 10, "y" -> 20)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Map[String, Int]](json)
    v shouldBe v2
  }

  test("nested Map[String, List[Int]]") {
    val v       = Map("numbers" -> List(1, 2, 3), "more" -> List(4, 5), "empty" -> List.empty[Int])
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, List[Int]]](msgpack)
    v shouldBe v2
  }

  test("nested Map[String, Map[String, Int]]") {
    val v = Map(
      "group1" -> Map("a" -> 1, "b" -> 2),
      "group2" -> Map("x" -> 10, "y" -> 20),
      "empty"  -> Map.empty[String, Int]
    )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Map[String, Map[String, Int]]](msgpack)
    v shouldBe v2
  }

  test("handle malformed map data gracefully") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack where we claim there are more pairs than we provide
    val packer = MessagePack.newPacker()
    packer.packMapHeader(3)   // Say we have 3 key-value pairs
    packer.packString("key1") // Valid first key
    packer.packInt(1)         // Valid first value
    packer.packString("key2") // Valid second key
    packer.packInt(2)         // Valid second value
    // Missing third key-value pair!

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[Map[String, Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
  }

  test("handle malformed map value gracefully") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack map with wrong value type
    val packer = MessagePack.newPacker()
    packer.packMapHeader(2)      // Say we have 2 key-value pairs
    packer.packString("key1")    // Valid first key
    packer.packInt(1)            // Valid first value
    packer.packString("key2")    // Valid second key
    packer.packString("invalid") // Invalid second value for Map[String, Int]

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[Map[String, Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  test("weave Seq[Int]") {
    val v       = Seq(1, 2, 3, 4, 5)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Seq[Int]](msgpack)
    v shouldBe v2
  }

  test("weave empty Seq[Int]") {
    val v       = Seq.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Seq[Int]](msgpack)
    v shouldBe v2
  }

  test("weave Seq[String]") {
    val v       = Seq("hello", "world", "test")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Seq[String]](msgpack)
    v shouldBe v2
  }

  test("Seq[Int] toJson") {
    val v    = Seq(1, 2, 3)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Seq[Int]](json)
    v shouldBe v2
  }

  test("nested Seq[Seq[Int]]") {
    val v       = Seq(Seq(1, 2), Seq(3, 4), Seq(5))
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Seq[Seq[Int]]](msgpack)
    v shouldBe v2
  }

  test("weave IndexedSeq[Int]") {
    val v       = IndexedSeq(1, 2, 3, 4, 5)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[IndexedSeq[Int]](msgpack)
    v shouldBe v2
  }

  test("weave empty IndexedSeq[Int]") {
    val v       = IndexedSeq.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[IndexedSeq[Int]](msgpack)
    v shouldBe v2
  }

  test("weave IndexedSeq[String]") {
    val v       = IndexedSeq("hello", "world", "test")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[IndexedSeq[String]](msgpack)
    v shouldBe v2
  }

  test("IndexedSeq[Int] toJson") {
    val v    = IndexedSeq(1, 2, 3)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[IndexedSeq[Int]](json)
    v shouldBe v2
  }

  test("nested IndexedSeq[IndexedSeq[Int]]") {
    val v       = IndexedSeq(IndexedSeq(1, 2), IndexedSeq(3, 4), IndexedSeq(5))
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[IndexedSeq[IndexedSeq[Int]]](msgpack)
    v shouldBe v2
  }

  test("weave java.util.List[Int]") {
    val v       = List(1, 2, 3, 4, 5).asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.List[Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave empty java.util.List[Int]") {
    val v       = List.empty[Int].asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.List[Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave java.util.List[String]") {
    val v       = List("hello", "world", "test").asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.List[String]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("java.util.List[Int] toJson") {
    val v    = List(1, 2, 3).asJava
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[java.util.List[Int]](json)
    v2.asScala shouldBe v.asScala
  }

  test("nested java.util.List[java.util.List[Int]]") {
    val v       = List(List(1, 2).asJava, List(3, 4).asJava, List(5).asJava).asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.List[java.util.List[Int]]](msgpack)
    v2.asScala.map(_.asScala) shouldBe v.asScala.map(_.asScala)
  }

  test("handle malformed array data gracefully for Seq") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack array with wrong element count
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(3)    // Say we have 3 elements
    packer.packInt(1)            // Valid first element
    packer.packString("invalid") // Invalid second element for Seq[Int]
    packer.packInt(3)            // Third element that should be skipped

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[Seq[Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  test("handle malformed array data gracefully for IndexedSeq") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack array with wrong element count
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(3)    // Say we have 3 elements
    packer.packInt(1)            // Valid first element
    packer.packString("invalid") // Invalid second element for IndexedSeq[Int]
    packer.packInt(3)            // Third element that should be skipped

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[IndexedSeq[Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  test("handle malformed array data gracefully for java.util.List") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack array with wrong element count
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(3)    // Say we have 3 elements
    packer.packInt(1)            // Valid first element
    packer.packString("invalid") // Invalid second element for java.util.List[Int]
    packer.packInt(3)            // Third element that should be skipped

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[java.util.List[Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  test("weave ListMap[String, Int]") {
    val v       = scala.collection.immutable.ListMap("a" -> 1, "b" -> 2, "c" -> 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[scala.collection.immutable.ListMap[String, Int]](msgpack)
    v shouldBe v2
    // Verify order is preserved
    v.keys.toList shouldBe v2.keys.toList
    v.values.toList shouldBe v2.values.toList
  }

  test("weave empty ListMap[String, Int]") {
    val v       = scala.collection.immutable.ListMap.empty[String, Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[scala.collection.immutable.ListMap[String, Int]](msgpack)
    v shouldBe v2
  }

  test("weave ListMap[Int, String]") {
    val v       = scala.collection.immutable.ListMap(1 -> "one", 2 -> "two", 3 -> "three")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[scala.collection.immutable.ListMap[Int, String]](msgpack)
    v shouldBe v2
    // Verify order is preserved
    v.keys.toList shouldBe v2.keys.toList
    v.values.toList shouldBe v2.values.toList
  }

  test("ListMap[String, Int] toJson") {
    val v    = scala.collection.immutable.ListMap("x" -> 10, "y" -> 20, "z" -> 30)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[scala.collection.immutable.ListMap[String, Int]](json)
    v shouldBe v2
    // Verify order is preserved
    v.keys.toList shouldBe v2.keys.toList
    v.values.toList shouldBe v2.values.toList
  }

  test("nested ListMap[String, List[Int]]") {
    val v = scala
      .collection
      .immutable
      .ListMap("numbers" -> List(1, 2, 3), "more" -> List(4, 5), "empty" -> List.empty[Int])
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[scala.collection.immutable.ListMap[String, List[Int]]](msgpack)
    v shouldBe v2
    // Verify order is preserved
    v.keys.toList shouldBe v2.keys.toList
  }

  test("nested ListMap[String, ListMap[String, Int]]") {
    val v = scala
      .collection
      .immutable
      .ListMap(
        "group1" -> scala.collection.immutable.ListMap("a" -> 1, "b" -> 2),
        "group2" -> scala.collection.immutable.ListMap("x" -> 10, "y" -> 20),
        "empty"  -> scala.collection.immutable.ListMap.empty[String, Int]
      )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[
      scala.collection.immutable.ListMap[String, scala.collection.immutable.ListMap[String, Int]]
    ](msgpack)
    v shouldBe v2
    // Verify order is preserved for outer map
    v.keys.toList shouldBe v2.keys.toList
    // Verify order is preserved for inner maps
    v("group1").keys.toList shouldBe v2("group1").keys.toList
    v("group2").keys.toList shouldBe v2("group2").keys.toList
  }

  test("ListMap preserves insertion order") {
    // Create ListMap with specific order
    val builder  = scala.collection.immutable.ListMap.newBuilder[String, Int]
    builder += ("third"  -> 3)
    builder += ("first"  -> 1)
    builder += ("second" -> 2)
    val v        = builder.result()

    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[scala.collection.immutable.ListMap[String, Int]](msgpack)

    // Verify values are correct
    v shouldBe v2
    // Verify insertion order is preserved
    v.keys.toList shouldBe List("third", "first", "second")
    v2.keys.toList shouldBe List("third", "first", "second")
    v.values.toList shouldBe List(3, 1, 2)
    v2.values.toList shouldBe List(3, 1, 2)
  }

  test("handle malformed ListMap data gracefully") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack where we claim there are more pairs than we provide
    val packer = MessagePack.newPacker()
    packer.packMapHeader(3)   // Say we have 3 key-value pairs
    packer.packString("key1") // Valid first key
    packer.packInt(1)         // Valid first value
    packer.packString("key2") // Valid second key
    packer.packInt(2)         // Valid second value
    // Missing third key-value pair!

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[scala.collection.immutable.ListMap[String, Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
  }

  test("handle malformed ListMap value gracefully") {
    import wvlet.uni.msgpack.spi.MessagePack
    // Create a malformed msgpack map with wrong value type
    val packer = MessagePack.newPacker()
    packer.packMapHeader(2)      // Say we have 2 key-value pairs
    packer.packString("key1")    // Valid first key
    packer.packInt(1)            // Valid first value
    packer.packString("key2")    // Valid second key
    packer.packString("invalid") // Invalid second value for ListMap[String, Int]

    val malformedMsgpack = packer.toByteArray

    val result =
      try
        Weaver.unweave[scala.collection.immutable.ListMap[String, Int]](malformedMsgpack)
        None
      catch
        case e: Exception =>
          Some(e)

    result.isDefined shouldBe true
    result.get.getMessage.contains("Cannot convert") shouldBe true
  }

  // ====== java.util.Map ======

  test("weave java.util.Map[String, Int]") {
    val v       = Map("a" -> 1, "b" -> 2, "c" -> 3).asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Map[String, Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave empty java.util.Map[String, Int]") {
    val v       = Map.empty[String, Int].asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Map[String, Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave java.util.Map[Int, String]") {
    val v       = Map(1 -> "one", 2 -> "two").asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Map[Int, String]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("java.util.Map[String, Int] toJson") {
    val v    = Map("x" -> 10, "y" -> 20).asJava
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[java.util.Map[String, Int]](json)
    v2.asScala shouldBe v.asScala
  }

  // ====== java.util.Set ======

  test("weave java.util.Set[Int]") {
    val v       = Set(1, 2, 3).asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Set[Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave empty java.util.Set[Int]") {
    val v       = Set.empty[Int].asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Set[Int]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("weave java.util.Set[String]") {
    val v       = Set("hello", "world").asJava
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[java.util.Set[String]](msgpack)
    v2.asScala shouldBe v.asScala
  }

  test("java.util.Set[Int] toJson") {
    val v    = Set(1, 2, 3).asJava
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[java.util.Set[Int]](json)
    v2.asScala shouldBe v.asScala
  }

  // ====== Tuples ======

  test("weave EmptyTuple") {
    val v       = EmptyTuple
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[EmptyTuple](msgpack)
    v2 shouldBe v
  }

  test("weave Tuple1 (Int)") {
    val v: Tuple1[Int] = Tuple1(42)
    val msgpack        = Weaver.weave(v)
    val v2             = Weaver.unweave[Tuple1[Int]](msgpack)
    v2 shouldBe v
  }

  test("weave (Int, String)") {
    val v       = (1, "hello")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[(Int, String)](msgpack)
    v2 shouldBe v
  }

  test("weave (Int, String, Boolean)") {
    val v       = (1, "hello", true)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[(Int, String, Boolean)](msgpack)
    v2 shouldBe v
  }

  test("weave (Int, String, Double, Long)") {
    val v       = (1, "hello", 3.14, 42L)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[(Int, String, Double, Long)](msgpack)
    v2 shouldBe v
  }

  test("(Int, String) toJson") {
    val v    = (1, "hello")
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[(Int, String)](json)
    v2 shouldBe v
  }

  test("nested tuple (Int, List[String])") {
    val v       = (42, List("a", "b", "c"))
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[(Int, List[String])](msgpack)
    v2 shouldBe v
  }

  // Regression coverage for fromSurfaceOpt: ensure unsupported nested types are detected even
  // when wrapped inside collections or case classes — otherwise callers that key behavior on
  // "is this weaver lossless" would silently emit `[{}]` / `{"d":{}}`.
  test("fromSurfaceOpt returns None for top-level Either") {
    import wvlet.uni.surface.Surface
    Weaver.fromSurfaceOpt(Surface.of[Either[String, Int]]) shouldBe None
  }

  test("fromSurfaceOpt returns None for Seq with unsupported element type") {
    import wvlet.uni.surface.Surface
    Weaver.fromSurfaceOpt(Surface.of[Seq[Either[String, Int]]]) shouldBe None
  }

  test("fromSurfaceOpt returns None for case class with unsupported field type") {
    import wvlet.uni.surface.Surface
    Weaver.fromSurfaceOpt(Surface.of[WeaverTest.HasEither]) shouldBe None
  }

  test("fromSurfaceOpt returns Some for fully-supported types") {
    import wvlet.uni.surface.Surface
    Weaver.fromSurfaceOpt(Surface.of[WeaverTest.Greeting]).isDefined shouldBe true
    Weaver.fromSurfaceOpt(Surface.of[Seq[WeaverTest.Greeting]]).isDefined shouldBe true
    Weaver.fromSurfaceOpt(Surface.of[Map[String, WeaverTest.Greeting]]).isDefined shouldBe true
  }

  test("fromSurfaceOpt returns None when fromSurface itself throws") {
    // java.math.BigInteger is marked primitive in Surface but has no primitiveFactory branch,
    // so fromSurface throws IllegalArgumentException. fromSurfaceOpt must convert that to None
    // so RouterHandler doesn't fail at handler-construction time for routes returning a type
    // that transitively contains BigInteger.
    import wvlet.uni.surface.Surface
    Weaver.fromSurfaceOpt(Surface.of[java.math.BigInteger]) shouldBe None
    Weaver.fromSurfaceOpt(Surface.of[Seq[java.math.BigInteger]]) shouldBe None
  }

end WeaverTest

object WeaverTest:
  case class HasEither(e: Either[String, Int])
  case class Greeting(message: String)
