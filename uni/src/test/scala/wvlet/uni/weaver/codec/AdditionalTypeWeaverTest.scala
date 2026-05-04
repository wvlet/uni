package wvlet.uni.weaver.codec

import wvlet.uni.test.UniTest
import wvlet.uni.util.ElapsedTime
import wvlet.uni.util.ULID
import wvlet.uni.weaver.Weaver
import scala.concurrent.duration.Duration as ScalaDuration
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class AdditionalTypeWeaverTest extends UniTest:

  // ====== Set[A] ======

  test("roundtrip Set[Int]") {
    val v       = Set(1, 2, 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Set[Int]](msgpack)
    v2 shouldBe v
  }

  test("roundtrip Set[String]") {
    val v       = Set("a", "b", "c")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Set[String]](msgpack)
    v2 shouldBe v
  }

  test("empty Set") {
    val v       = Set.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Set[Int]](msgpack)
    v2 shouldBe v
  }

  test("Set to/from JSON") {
    val v    = Set(1, 2, 3)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Set[Int]](json)
    v2 shouldBe v
  }

  // ====== BigInt ======

  test("roundtrip BigInt (fits in Long)") {
    val v       = BigInt(123456789L)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[BigInt](msgpack)
    v2 shouldBe v
  }

  test("roundtrip BigInt (exceeds Long)") {
    val v       = BigInt("123456789012345678901234567890")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[BigInt](msgpack)
    v2 shouldBe v
  }

  test("BigInt to/from JSON") {
    val v    = BigInt(42)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[BigInt](json)
    v2 shouldBe v
  }

  test("BigInt negative") {
    val v       = BigInt("-99999999999999999999")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[BigInt](msgpack)
    v2 shouldBe v
  }

  // ====== BigDecimal ======

  test("roundtrip BigDecimal") {
    val v       = BigDecimal("123.456789012345678901234567890")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[BigDecimal](msgpack)
    v2 shouldBe v
  }

  test("BigDecimal to/from JSON") {
    val v    = BigDecimal("3.14159")
    val json = Weaver.toJson(v)
    json shouldBe "\"3.14159\""
    val v2 = Weaver.fromJson[BigDecimal](json)
    v2 shouldBe v
  }

  test("BigDecimal from integer") {
    val json = "42"
    val v    = Weaver.fromJson[BigDecimal](json)
    v shouldBe BigDecimal(42)
  }

  test("BigDecimal from float") {
    val json = "3.14"
    val v    = Weaver.fromJson[BigDecimal](json)
    v shouldBe BigDecimal(3.14)
  }

  // ====== Either[A,B] ======

  test("roundtrip Either Left") {
    val v: Either[String, Int] = Left("error")
    val msgpack                = Weaver.weave(v)
    val v2                     = Weaver.unweave[Either[String, Int]](msgpack)
    v2 shouldBe v
  }

  test("roundtrip Either Right") {
    val v: Either[String, Int] = Right(42)
    val msgpack                = Weaver.weave(v)
    val v2                     = Weaver.unweave[Either[String, Int]](msgpack)
    v2 shouldBe v
  }

  test("Either Left to/from JSON") {
    val v: Either[String, Int] = Left("fail")
    val json                   = Weaver.toJson(v)
    json shouldContain "\"fail\""
    val v2 = Weaver.fromJson[Either[String, Int]](json)
    v2 shouldBe v
  }

  test("Either Right to/from JSON") {
    val v: Either[String, Int] = Right(100)
    val json                   = Weaver.toJson(v)
    val v2                     = Weaver.fromJson[Either[String, Int]](json)
    v2 shouldBe v
  }

  test("Either wrong array size") {
    val e = intercept[IllegalArgumentException] {
      Weaver.fromJson[Either[String, Int]]("[1,2,3]")
    }
    e.getMessage shouldContain "size 2"
  }

  // ====== Instant ======

  test("roundtrip Instant") {
    val v       = Instant.parse("2024-01-15T10:30:00Z")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Instant](msgpack)
    v2 shouldBe v
  }

  test("Instant with nanoseconds") {
    val v       = Instant.parse("2024-06-15T12:30:45.123456789Z")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Instant](msgpack)
    v2 shouldBe v
  }

  test("Instant from epoch millis JSON") {
    val epochMillis = 1705312200000L
    val json        = epochMillis.toString
    val v           = Weaver.fromJson[Instant](json)
    v shouldBe Instant.ofEpochMilli(epochMillis)
  }

  test("Instant from ISO string JSON") {
    val json = "\"2024-01-15T10:30:00Z\""
    val v    = Weaver.fromJson[Instant](json)
    v shouldBe Instant.parse("2024-01-15T10:30:00Z")
  }

  // ====== UUID ======

  test("roundtrip UUID") {
    val v       = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[UUID](msgpack)
    v2 shouldBe v
  }

  test("UUID to/from JSON") {
    val v    = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val json = Weaver.toJson(v)
    json shouldBe "\"550e8400-e29b-41d4-a716-446655440000\""
    val v2 = Weaver.fromJson[UUID](json)
    v2 shouldBe v
  }

  test("UUID another roundtrip") {
    val v       = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[UUID](msgpack)
    v2 shouldBe v
  }

  test("UUID invalid string") {
    val e = intercept[IllegalArgumentException] {
      Weaver.fromJson[UUID]("\"not-a-uuid\"")
    }
    e.getMessage shouldContain "UUID"
  }

  // ====== Array[A] ======

  test("roundtrip Array[Int]") {
    val v       = Array(1, 2, 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Array[Int]](msgpack)
    v2.toList shouldBe v.toList
  }

  test("roundtrip Array[String]") {
    val v       = Array("hello", "world")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Array[String]](msgpack)
    v2.toList shouldBe v.toList
  }

  test("empty Array") {
    val v       = Array.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Array[Int]](msgpack)
    v2.toList shouldBe v.toList
  }

  test("Array to/from JSON") {
    val v    = Array(1, 2, 3)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Array[Int]](json)
    v2.toList shouldBe v.toList
  }

  // ====== Vector[A] ======

  test("roundtrip Vector[Int]") {
    val v       = Vector(1, 2, 3)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Vector[Int]](msgpack)
    v2 shouldBe v
  }

  test("roundtrip Vector[String]") {
    val v       = Vector("a", "b", "c")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Vector[String]](msgpack)
    v2 shouldBe v
  }

  test("empty Vector") {
    val v       = Vector.empty[Int]
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Vector[Int]](msgpack)
    v2 shouldBe v
  }

  test("Vector to/from JSON") {
    val v    = Vector(10, 20, 30)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Vector[Int]](json)
    v2 shouldBe v
  }

  // ====== scala.concurrent.duration.Duration ======

  test("roundtrip scala Duration") {
    val v       = ScalaDuration("5 seconds")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[ScalaDuration](msgpack)
    v2 shouldBe v
  }

  test("scala Duration to/from JSON") {
    val v    = ScalaDuration("10 minutes")
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[ScalaDuration](json)
    v2 shouldBe v
  }

  test("scala Duration Inf") {
    val v: ScalaDuration = ScalaDuration.Inf
    val msgpack          = Weaver.weave(v)
    val v2               = Weaver.unweave[ScalaDuration](msgpack)
    v2 shouldBe v
  }

  // ====== URI ======

  test("roundtrip URI") {
    val v       = URI("https://example.com/path?query=1")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[URI](msgpack)
    v2 shouldBe v
  }

  test("URI to/from JSON") {
    val v    = URI("file:///tmp/test.txt")
    val json = Weaver.toJson(v)
    json shouldBe "\"file:///tmp/test.txt\""
    val v2 = Weaver.fromJson[URI](json)
    v2 shouldBe v
  }

  // ====== ULID ======

  test("roundtrip ULID") {
    val v       = ULID("01arz3ndektsv4rrffq69g5fav")
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[ULID](msgpack)
    v2 shouldBe v
  }

  test("ULID to/from JSON") {
    val v    = ULID("01arz3ndektsv4rrffq69g5fav")
    val json = Weaver.toJson(v)
    json shouldBe "\"01arz3ndektsv4rrffq69g5fav\""
    val v2 = Weaver.fromJson[ULID](json)
    v2 shouldBe v
  }

  test("ULID via fromSurface") {
    import wvlet.uni.surface.Surface
    val w  = Weaver.fromSurface(Surface.of[ULID]).asInstanceOf[Weaver[ULID]]
    val v  = ULID("01arz3ndektsv4rrffq69g5fav")
    val s  = w.toJson(v)
    val v2 = w.fromJson(s)
    v2 shouldBe v
  }

  test("ULID invalid string") {
    val e = intercept[IllegalArgumentException] {
      Weaver.fromJson[ULID]("\"not-a-ulid\"")
    }
    e.getMessage shouldContain "ULID"
  }

  // ====== ElapsedTime ======

  test("roundtrip ElapsedTime") {
    val v       = ElapsedTime(5.0, TimeUnit.MILLISECONDS)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[ElapsedTime](msgpack)
    v2 shouldBe v
  }

  test("ElapsedTime to/from JSON") {
    val v    = ElapsedTime(2.5, TimeUnit.SECONDS)
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[ElapsedTime](json)
    v2 shouldBe v
  }

  test("ElapsedTime via fromSurface") {
    import wvlet.uni.surface.Surface
    val w  = Weaver.fromSurface(Surface.of[ElapsedTime]).asInstanceOf[Weaver[ElapsedTime]]
    val v  = ElapsedTime(123.0, TimeUnit.MILLISECONDS)
    val s  = w.toJson(v)
    val v2 = w.fromJson(s)
    v2 shouldBe v
  }

  test("ElapsedTime invalid string") {
    val e = intercept[IllegalArgumentException] {
      Weaver.fromJson[ElapsedTime]("\"not-a-duration\"")
    }
    e.getMessage shouldContain "duration"
  }

  test("ElapsedTime preserves full Double precision") {
    // Regression: toString-based encoding rounded to 2 decimals and lost extreme values.
    val v       = ElapsedTime(2.555, TimeUnit.SECONDS)
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[ElapsedTime](msgpack)
    v2.value shouldBe v.value
    v2.unit shouldBe v.unit

    val tiny     = ElapsedTime(1.0e-5, TimeUnit.NANOSECONDS)
    val tinyJson = Weaver.toJson(tiny)
    val tinyBack = Weaver.fromJson[ElapsedTime](tinyJson)
    tinyBack.value shouldBe tiny.value
    tinyBack.unit shouldBe tiny.unit
  }

  test("ElapsedTime accepts legacy string form for backward compatibility") {
    val legacy = "\"2.50ms\""
    val v      = Weaver.fromJson[ElapsedTime](legacy)
    v.value shouldBe 2.5
    v.unit shouldBe TimeUnit.MILLISECONDS
  }

  test("ElapsedTime rejects map missing value or unit") {
    val noValue = """{"unit":"ms"}"""
    val e1      = intercept[IllegalArgumentException] {
      Weaver.fromJson[ElapsedTime](noValue)
    }
    e1.getMessage shouldContain "value"

    val noUnit = """{"value":5.0}"""
    val e2     = intercept[IllegalArgumentException] {
      Weaver.fromJson[ElapsedTime](noUnit)
    }
    e2.getMessage shouldContain "unit"
  }

  // ====== Composite: case class with new types ======

  case class Record(id: UUID, tags: Set[String], amount: BigDecimal, createdAt: Instant)
      derives Weaver

  test("case class with new types") {
    val v = Record(
      id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
      tags = Set("a", "b"),
      amount = BigDecimal("99.99"),
      createdAt = Instant.parse("2024-01-15T10:30:00Z")
    )
    val msgpack = Weaver.weave(v)
    val v2      = Weaver.unweave[Record](msgpack)
    v2 shouldBe v
  }

  test("case class with new types JSON roundtrip") {
    val v = Record(
      id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
      tags = Set("x"),
      amount = BigDecimal("0.01"),
      createdAt = Instant.parse("2024-06-01T00:00:00Z")
    )
    val json = Weaver.toJson(v)
    val v2   = Weaver.fromJson[Record](json)
    v2 shouldBe v
  }

end AdditionalTypeWeaverTest
