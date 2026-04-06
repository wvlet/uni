package wvlet.uni.util

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

import java.util.UUID // For serialization test

class UUIDv7Test extends UniTest:

  test("UUIDv7.newUUIDv7() should generate valid UUIDv7") {
    val uuid = UUIDv7.newUUIDv7()
    uuid.version shouldBe 7
    uuid.variant shouldBe 2 // RFC 4122 variant

    val now      = System.currentTimeMillis()
    val uuidTime = uuid.timestamp

    // Allow for a small clock skew between generation and check
    assert((now - uuidTime) < 50L, "Timestamp should be recent (within 50ms)")
    assert(uuidTime > 0L, "Timestamp should be positive")
  }

  test("UUIDv7.newUUIDv7(timestamp) should generate UUIDv7 with specific timestamp") {
    val specificTime = System.currentTimeMillis() - 10000 // 10 seconds ago
    val uuid         = UUIDv7.newUUIDv7(specificTime)
    uuid.version shouldBe 7
    uuid.variant shouldBe 2
    uuid.timestamp shouldBe specificTime
  }

  test("UUIDv7.toString should return standard UUID format") {
    val uuid    = UUIDv7.newUUIDv7()
    val uuidStr = uuid.toString
    assert(uuidStr.matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"))
  }

  test("UUIDv7.fromString should parse valid UUIDv7 string") {
    val originalUUID = UUIDv7.newUUIDv7()
    val uuidStr      = originalUUID.toString
    val parsedUUID   = UUIDv7.fromString(uuidStr)

    parsedUUID.mostSignificantBits shouldBe originalUUID.mostSignificantBits
    parsedUUID.leastSignificantBits shouldBe originalUUID.leastSignificantBits
    parsedUUID.timestamp shouldBe originalUUID.timestamp
    parsedUUID.version shouldBe 7
    parsedUUID.variant shouldBe 2
  }

  test("UUIDv7.fromString should fail for invalid UUID string format") {
    intercept[IllegalArgumentException] {
      UUIDv7.fromString("invalid-uuid-string")
    }
  }

  test("UUIDv7.fromString should fail for non-UUIDv7 (wrong version)") {
    // Construct a V4 UUID string for testing
    val randomMsbForV4String = 0x1111222233334444L
    val randomLsbForV4String = 0x5555666677778888L
    val msbV4 = (randomMsbForV4String & ~0x000000000000f000L) | (4L << 12) // Set version to 4
    val lsbV4 =
      (randomLsbForV4String & ~0xc000000000000000L) | (2L << 62) // Set variant to RFC4122 (10xx)
    val uuidV4String = new UUID(msbV4, lsbV4).toString

    val ex = intercept[IllegalArgumentException] {
      UUIDv7.fromString(uuidV4String)
    }
    // The version of the constructed UUID will be 4
    ex.getMessage shouldBe "Invalid UUIDv7 string: version is 4, expected 7"
  }

  test("UUIDv7.fromUUID and toUUID conversion") {
    val u7    = UUIDv7.newUUIDv7()
    val juuid = u7.toUUID

    juuid.version() shouldBe 7 // Standard java.util.UUID version method
    juuid.variant() shouldBe 2

    val convertedU7 = UUIDv7.fromUUID(juuid)
    convertedU7 shouldBe u7
    convertedU7.timestamp shouldBe u7.timestamp
  }

  test("UUIDv7.fromUUID should fail for non-UUIDv7 (wrong version)") {
    // Construct a V4 UUID object for testing
    val randomMsbForV4Obj = 0xaaaabbbbccccddddL
    val randomLsbForV4Obj = 0xeeeeffff00001111L
    val msbV4Obj = (randomMsbForV4Obj & ~0x000000000000f000L) | (4L << 12) // Set version to 4
    val lsbV4Obj =
      (randomLsbForV4Obj & ~0xc000000000000000L) | (2L << 62) // Set variant to RFC4122 (10xx)
    val uuidV4   = new UUID(msbV4Obj, lsbV4Obj)

    val ex = intercept[IllegalArgumentException] {
      UUIDv7.fromUUID(uuidV4)
    }
    ex.getMessage shouldBe s"Invalid UUID: version is ${uuidV4.version()}, expected 7 for UUIDv7"
  }

  test("UUIDv7.compareTo should correctly order UUIDv7s") {
    val time1 = System.currentTimeMillis()
    val uuid1 = UUIDv7.newUUIDv7(time1)
    ThreadUtil.sleep(2) // Ensure different timestamp or random part
    val time2 = System.currentTimeMillis()
    val uuid2 = UUIDv7.newUUIDv7(time2)
    ThreadUtil.sleep(2)
    val uuid3 = UUIDv7.newUUIDv7(time2) // Potentially same timestamp, different random

    assert(uuid1.compareTo(uuid2) < 0)
    assert(uuid2.compareTo(uuid1) > 0)
    uuid1.compareTo(uuid1) shouldBe 0

    if uuid2.timestamp == uuid3.timestamp then
      assert(uuid2.compareTo(uuid3) < 0)
    else
      assert(uuid2.timestamp < uuid3.timestamp) // if clock ticked
  }

  test("UUIDv7.equals and hashCode") {
    val uuid1   = UUIDv7.newUUIDv7()
    val uuidStr = uuid1.toString
    val uuid2   = UUIDv7.fromString(uuidStr)
    val uuid3   = UUIDv7.newUUIDv7()

    uuid1 shouldBe uuid2 // Uses .equals
    uuid2 shouldBe uuid1 // Uses .equals
    uuid1.hashCode() shouldBe uuid2.hashCode()

    uuid1 shouldNotBe uuid3

    assert(!uuid1.equals(null))
    assert(!uuid1.equals("some string"))
  }

  test("UUIDv7 monotonicity for rapid generation") {
    val N     = 1000
    val uuids = (1 to N).map(_ => UUIDv7.newUUIDv7()).toList

    var i = 0
    while i < N - 1 do
      val u1 = uuids(i)
      val u2 = uuids(i + 1)
      assert(u1.compareTo(u2) < 0)
      assert(u1.timestamp <= u2.timestamp)
      i += 1
    uuids.foreach { u =>
      u.version shouldBe 7
      u.variant shouldBe 2
    }
  }

  test("UUIDv7 timestamp boundaries") {
    val minTimeUUID = UUIDv7.newUUIDv7(0L)
    minTimeUUID.timestamp shouldBe 0L
    minTimeUUID.version shouldBe 7
    minTimeUUID.variant shouldBe 2

    val maxTimeMillis = (1L << 48) - 1
    val maxTimeUUID   = UUIDv7.newUUIDv7(maxTimeMillis)
    maxTimeUUID.timestamp shouldBe maxTimeMillis
    maxTimeUUID.version shouldBe 7
    maxTimeUUID.variant shouldBe 2

    val overMaxTimeUUID = UUIDv7.newUUIDv7(maxTimeMillis + 1000L)
    overMaxTimeUUID.timestamp shouldBe maxTimeMillis

    val underMinTimeUUID = UUIDv7.newUUIDv7(-1000L)
    underMinTimeUUID.timestamp shouldBe 0L
  }

  test("UUIDv7.toBytes and UUIDv7.fromBytes conversion") {
    val originalUUID = UUIDv7.newUUIDv7()
    val bytes        = UUIDv7.toBytes(originalUUID)
    bytes.length shouldBe 16

    val recoveredUUID = UUIDv7.fromBytes(bytes)
    recoveredUUID shouldBe originalUUID
    recoveredUUID.timestamp shouldBe originalUUID.timestamp
    recoveredUUID.version shouldBe originalUUID.version
    recoveredUUID.variant shouldBe originalUUID.variant
  }

  test("UUIDv7.fromBytes should fail for invalid byte array length") {
    intercept[IllegalArgumentException] {
      UUIDv7.fromBytes(new Array[Byte](15))
    }
    intercept[IllegalArgumentException] {
      UUIDv7.fromBytes(new Array[Byte](17))
    }
  }

  test(
    "UUIDv7.fromBytes should fail for bytes not representing UUIDv7 (e.g. wrong version/variant)"
  ) {
    // Construct bytes for a non-UUIDv7 (e.g., a UUIDv4)
    val randomMsbForBytes = 0x1234abcd5678ef00L
    val randomLsbForBytes = 0x00fedcba98765432L
    val msbV4Bytes = (randomMsbForBytes & ~0x000000000000f000L) | (4L << 12) // Set version to 4
    val lsbV4Bytes =
      (randomLsbForBytes & ~0xc000000000000000L) | (2L << 62) // Set variant to RFC4122 (10xx)

    val bytes = new Array[Byte](16)
    val bb    = java.nio.ByteBuffer.wrap(bytes)
    bb.putLong(msbV4Bytes)
    bb.putLong(lsbV4Bytes)

    val ex = intercept[IllegalArgumentException] {
      UUIDv7.fromBytes(bytes)
    }
    ex.getMessage shouldBe
      "Bytes do not represent a valid UUIDv7 structure (version/variant mismatch)"
  }

  test("UUIDv7.toBase62 produces 22-character string") {
    val uuid   = UUIDv7.newUUIDv7()
    val base62 = uuid.toBase62
    base62.length shouldBe 22
    (Base62.isValid(base62) shouldBe true)
  }

  test("UUIDv7 Base62 roundtrip") {
    for _ <- 0 until 100 do
      val original  = UUIDv7.newUUIDv7()
      val base62    = original.toBase62
      val recovered = UUIDv7.fromBase62(base62)
      recovered shouldBe original
      recovered.timestamp shouldBe original.timestamp
  }

  test("UUIDv7 Base62 sort order preserved") {
    val uuids   = (1 to 50).map(_ => UUIDv7.newUUIDv7())
    val base62s = uuids.map(_.toBase62)
    base62s
      .sliding(2)
      .foreach { pair =>
        val a = pair(0)
        val b = pair(1)
        assert(a < b, s"Expected ${a} < ${b}")
      }
  }

  test("UUIDv7.fromBase62 rejects invalid UUIDv7 (wrong version)") {
    // Encode a non-UUIDv7 value
    val msbV4  = (0x1111222233334444L & ~0x000000000000f000L) | (4L << 12)
    val lsbV4  = (0x5555666677778888L & ~0xc000000000000000L) | (2L << 62)
    val base62 = Base62.encode128bits(msbV4, lsbV4)
    intercept[IllegalArgumentException] {
      UUIDv7.fromBase62(base62)
    }
  }

end UUIDv7Test
