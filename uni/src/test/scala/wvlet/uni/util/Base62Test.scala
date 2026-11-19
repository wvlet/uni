package wvlet.uni.util

import wvlet.uni.test.UniTest

class Base62Test extends UniTest:

  test("encode and decode 128-bit zero value") {
    val encoded = Base62.encode128bits(0L, 0L)
    encoded.length shouldBe 22
    encoded shouldBe "0000000000000000000000"
    val (hi, low) = Base62.decode128bits(encoded)
    hi shouldBe 0L
    low shouldBe 0L
  }

  test("encode and decode 128-bit max value") {
    val encoded = Base62.encode128bits(-1L, -1L) // all bits set
    encoded.length shouldBe 22
    val (hi, low) = Base62.decode128bits(encoded)
    hi shouldBe -1L
    low shouldBe -1L
  }

  test("roundtrip for various values") {
    val cases = Seq(
      (0L, 1L),
      (1L, 0L),
      (0x0123456789abcdefL, 0xfedcba9876543210L),
      (Long.MaxValue, Long.MinValue),
      (Long.MinValue, Long.MaxValue)
    )
    cases.foreach { (hi, low) =>
      val encoded     = Base62.encode128bits(hi, low)
      val (dHi, dLow) = Base62.decode128bits(encoded)
      dHi shouldBe hi
      dLow shouldBe low
    }
  }

  test("encoded strings are always 22 characters") {
    for _ <- 0 until 100 do
      val uuid    = UUIDv7.newUUIDv7()
      val encoded = Base62.encode128bits(uuid.mostSignificantBits, uuid.leastSignificantBits)
      encoded.length shouldBe 22
  }

  test("sort order is preserved") {
    // Generate UUIDv7s which are monotonically increasing
    val uuids   = (1 to 50).map(_ => UUIDv7.newUUIDv7())
    val encoded = uuids.map(u =>
      Base62.encode128bits(u.mostSignificantBits, u.leastSignificantBits)
    )
    // Verify lexicographic order matches
    encoded
      .sliding(2)
      .foreach { pair =>
        val a = pair(0)
        val b = pair(1)
        assert(a < b, s"Expected ${a} < ${b}")
      }
  }

  test("decode rejects invalid length") {
    intercept[IllegalArgumentException] {
      Base62.decode128bits("abc")
    }
  }

  test("decode rejects invalid characters") {
    intercept[IllegalArgumentException] {
      Base62.decode128bits("!@#$%^&*()!@#$%^&*()!@")
    }
  }

  test("isValid checks") {
    (Base62.isValid("0aZ9") shouldBe true)
    (Base62.isValid("hello!") shouldBe false)
    (Base62.isValid("") shouldBe true)
  }

  test("only uses 0-9A-Za-z characters") {
    for _ <- 0 until 100 do
      val uuid    = UUIDv7.newUUIDv7()
      val encoded = Base62.encode128bits(uuid.mostSignificantBits, uuid.leastSignificantBits)
      assert(encoded.forall(c => c.isDigit || c.isLetter), s"Invalid character in: ${encoded}")
      (Base62.isValid(encoded) shouldBe true)
  }

  test("decode rejects values exceeding 128-bit range") {
    // "zzzzzzzzzzzzzzzzzzzzzz" (all z's) is much larger than 2^128 - 1
    intercept[IllegalArgumentException] {
      Base62.decode128bits("zzzzzzzzzzzzzzzzzzzzzz")
    }
  }

  test("decode128bits(encode128bits(hi, low)) restores any 128-bit value") {
    forAll { (hi: Long, low: Long) =>
      val encoded = Base62.encode128bits(hi, low)
      encoded.length shouldBe 22
      Base62.isValid(encoded) shouldBe true
      Base62.decode128bits(encoded) shouldBe (hi, low)
    }
  }

  test("string order of encoded values matches unsigned 128-bit order") {
    forAll { (a: (Long, Long), b: (Long, Long)) =>
      val numeric =
        val c = java.lang.Long.compareUnsigned(a._1, b._1)
        if c != 0 then
          c
        else
          java.lang.Long.compareUnsigned(a._2, b._2)
      val encoded = Base62.encode128bits(a._1, a._2).compareTo(Base62.encode128bits(b._1, b._2))
      Integer.signum(encoded) shouldBe Integer.signum(numeric)
    }
  }

end Base62Test
