package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.weaver.Weaver
import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

class PrimitiveWeaverTest extends UniTest:

  test("unpack Int from INTEGER types") {
    // Test various integer formats
    val intValues = Seq(0, 1, -1, 127, -128, 32767, -32768, Int.MaxValue, Int.MinValue)

    for intValue <- intValues do
      val packed   = Weaver.weave(intValue)
      val unpacked = Weaver.unweave[Int](packed)
      unpacked shouldBe intValue
  }

  test("unpack Int from FLOAT types") {
    // Test whole number floats that can be converted to Int
    val testCases = Seq(
      (1.0, 1),
      (0.0, 0),
      (-1.0, -1),
      (42.0, 42),
      (Int.MaxValue.toDouble, Int.MaxValue),
      (Int.MinValue.toDouble, Int.MinValue)
    )

    for (floatValue, expectedInt) <- testCases do
      val packer = MessagePack.newPacker()
      packer.packDouble(floatValue)
      val packed   = packer.toByteArray
      val unpacked = Weaver.unweave[Int](packed)
      unpacked shouldBe expectedInt
  }

  test("unpack Int from FLOAT types - failure cases") {
    // Test floats that cannot be converted to Int
    val invalidFloats = Seq(1.5, -2.7, Double.PositiveInfinity, Double.NegativeInfinity, Double.NaN)

    for floatValue <- invalidFloats do
      val packer = MessagePack.newPacker()
      packer.packDouble(floatValue)
      val packed = packer.toByteArray

      val exception = intercept[IllegalArgumentException] {
        Weaver.unweave[Int](packed)
      }
      exception.getMessage.contains("Cannot convert double") shouldBe true
  }

  test("unpack Int from STRING types") {
    // Test valid numeric strings
    val testCases = Seq(
      ("0", 0),
      ("1", 1),
      ("-1", -1),
      ("42", 42),
      ("2147483647", Int.MaxValue),
      ("-2147483648", Int.MinValue)
    )

    for (stringValue, expectedInt) <- testCases do
      val packer = MessagePack.newPacker()
      packer.packString(stringValue)
      val packed   = packer.toByteArray
      val unpacked = Weaver.unweave[Int](packed)
      unpacked shouldBe expectedInt
  }

  test("unpack Int from STRING types - failure cases") {
    // Test invalid strings
    val invalidStrings = Seq("hello", "1.5", "", "2147483648", "-2147483649", "0x10")

    for stringValue <- invalidStrings do
      val packer = MessagePack.newPacker()
      packer.packString(stringValue)
      val packed = packer.toByteArray

      val exception = intercept[IllegalArgumentException] {
        Weaver.unweave[Int](packed)
      }
      exception.getMessage.contains("Cannot convert string") shouldBe true
  }

  test("unpack Int from BOOLEAN types") {
    // Test boolean to int conversion (true = 1, false = 0)
    val packer1 = MessagePack.newPacker()
    packer1.packBoolean(true)
    val packed1   = packer1.toByteArray
    val unpacked1 = Weaver.unweave[Int](packed1)
    unpacked1 shouldBe 1

    val packer2 = MessagePack.newPacker()
    packer2.packBoolean(false)
    val packed2   = packer2.toByteArray
    val unpacked2 = Weaver.unweave[Int](packed2)
    unpacked2 shouldBe 0
  }

  test("unpack Int from NIL type") {
    // Test nil should result in null context, which returns 0 as default
    val packer = MessagePack.newPacker()
    packer.packNil
    val packed   = packer.toByteArray
    val unpacked = Weaver.unweave[Int](packed)
    unpacked shouldBe 0
  }

  test("unpack Int from unsupported types") {
    // Test types that cannot be converted to Int
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(2)
    packer.packInt(1)
    packer.packInt(2)
    val packed = packer.toByteArray

    val exception = intercept[IllegalArgumentException] {
      Weaver.unweave[Int](packed)
    }
    exception.getMessage.contains("Cannot convert") shouldBe true
  }

  test("large integer edge cases") {
    // Test integers that are at the edge of Int range
    val testCases = Seq(
      Long.MaxValue,           // Should fail - too large for Int
      Long.MinValue,           // Should fail - too small for Int
      Int.MaxValue.toLong + 1, // Should fail - overflow
      Int.MinValue.toLong - 1  // Should fail - underflow
    )

    for longValue <- testCases do
      val packer = MessagePack.newPacker()
      packer.packLong(longValue)
      val packed = packer.toByteArray

      intercept[Exception] {
        Weaver.unweave[Int](packed)
      }
  }

  test("Long weaver basic operations") {
    val longValues = Seq(0L, 1L, -1L, Long.MaxValue, Long.MinValue, 9223372036854775807L)

    for longValue <- longValues do
      val packed   = Weaver.weave(longValue)
      val unpacked = Weaver.unweave[Long](packed)
      unpacked shouldBe longValue
  }

  test("Long weaver from various types") {
    // From boolean
    val packerBool = MessagePack.newPacker()
    packerBool.packBoolean(true)
    val packedBool   = packerBool.toByteArray
    val unpackedBool = Weaver.unweave[Long](packedBool)
    unpackedBool shouldBe 1L

    // From string
    val packerStr = MessagePack.newPacker()
    packerStr.packString("42")
    val packedStr   = packerStr.toByteArray
    val unpackedStr = Weaver.unweave[Long](packedStr)
    unpackedStr shouldBe 42L

    // From nil (should return default value for Long type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Long](packedNil)
    unpackedNil shouldBe 0L

    // From float (whole number)
    val packerFloat = MessagePack.newPacker()
    packerFloat.packDouble(123.0)
    val packedFloat   = packerFloat.toByteArray
    val unpackedFloat = Weaver.unweave[Long](packedFloat)
    unpackedFloat shouldBe 123L
  }

  test("Double weaver basic operations") {
    val doubleValues = Seq(0.0, 1.0, -1.0, 3.14159, Double.MaxValue, Double.MinValue)

    for doubleValue <- doubleValues do
      val packed   = Weaver.weave(doubleValue)
      val unpacked = Weaver.unweave[Double](packed)
      unpacked shouldBe doubleValue
  }

  test("Double weaver from various types") {
    // From integer
    val packerInt = MessagePack.newPacker()
    packerInt.packLong(42L)
    val packedInt   = packerInt.toByteArray
    val unpackedInt = Weaver.unweave[Double](packedInt)
    unpackedInt shouldBe 42.0

    // From string
    val packerStr = MessagePack.newPacker()
    packerStr.packString("3.14")
    val packedStr   = packerStr.toByteArray
    val unpackedStr = Weaver.unweave[Double](packedStr)
    unpackedStr shouldBe 3.14

    // From boolean
    val packerBool = MessagePack.newPacker()
    packerBool.packBoolean(false)
    val packedBool   = packerBool.toByteArray
    val unpackedBool = Weaver.unweave[Double](packedBool)
    unpackedBool shouldBe 0.0

    // From nil (should return default value for Double type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Double](packedNil)
    unpackedNil shouldBe 0.0
  }

  test("Float weaver basic operations") {
    val floatValues = Seq(0.0f, 1.0f, -1.0f, 3.14f, Float.MaxValue, Float.MinValue)

    for floatValue <- floatValues do
      val packed   = Weaver.weave(floatValue)
      val unpacked = Weaver.unweave[Float](packed)
      unpacked shouldBe floatValue
  }

  test("Float weaver from various types") {
    // From integer
    val packerInt = MessagePack.newPacker()
    packerInt.packLong(42L)
    val packedInt   = packerInt.toByteArray
    val unpackedInt = Weaver.unweave[Float](packedInt)
    unpackedInt shouldBe 42.0f

    // From double (within range)
    val packerDouble = MessagePack.newPacker()
    packerDouble.packDouble(3.14)
    val packedDouble   = packerDouble.toByteArray
    val unpackedDouble = Weaver.unweave[Float](packedDouble)
    unpackedDouble shouldBe 3.14f

    // From string
    val packerStr = MessagePack.newPacker()
    packerStr.packString("2.71")
    val packedStr   = packerStr.toByteArray
    val unpackedStr = Weaver.unweave[Float](packedStr)
    unpackedStr shouldBe 2.71f

    // From boolean
    val packerBool = MessagePack.newPacker()
    packerBool.packBoolean(true)
    val packedBool   = packerBool.toByteArray
    val unpackedBool = Weaver.unweave[Float](packedBool)
    unpackedBool shouldBe 1.0f

    // From nil (should return default value for Float type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Float](packedNil)
    unpackedNil shouldBe 0.0f
  }

  test("Float weaver range validation") {
    // Test double value outside float range
    val packer = MessagePack.newPacker()
    packer.packDouble(Double.MaxValue) // Too large for Float
    val packed = packer.toByteArray

    val exception = intercept[IllegalArgumentException] {
      Weaver.unweave[Float](packed)
    }
    exception.getMessage.contains("out of Float range") shouldBe true
  }

  test("Boolean weaver basic operations") {
    val boolValues = Seq(true, false)

    for boolValue <- boolValues do
      val packed   = Weaver.weave(boolValue)
      val unpacked = Weaver.unweave[Boolean](packed)
      unpacked shouldBe boolValue
  }

  test("Boolean weaver from various types") {
    // From integer - non-zero is true
    val packerInt = MessagePack.newPacker()
    packerInt.packInt(5)
    val packedInt   = packerInt.toByteArray
    val unpackedInt = Weaver.unweave[Boolean](packedInt)
    unpackedInt shouldBe true

    // From integer - zero is false
    val packerZero = MessagePack.newPacker()
    packerZero.packInt(0)
    val packedZero   = packerZero.toByteArray
    val unpackedZero = Weaver.unweave[Boolean](packedZero)
    unpackedZero shouldBe false

    // From string - various true values
    val trueStrings = Seq("true", "1", "yes", "on", "TRUE", "YES", "ON")
    for trueStr <- trueStrings do
      val packer = MessagePack.newPacker()
      packer.packString(trueStr)
      val packed   = packer.toByteArray
      val unpacked = Weaver.unweave[Boolean](packed)
      unpacked shouldBe true

    // From string - various false values
    val falseStrings = Seq("false", "0", "no", "off", "", "FALSE", "NO", "OFF")
    for falseStr <- falseStrings do
      val packer = MessagePack.newPacker()
      packer.packString(falseStr)
      val packed   = packer.toByteArray
      val unpacked = Weaver.unweave[Boolean](packed)
      unpacked shouldBe false

    // From float/double - non-zero is true, zero is false
    val packerDoubleTrue = MessagePack.newPacker()
    packerDoubleTrue.packDouble(3.14)
    val packedDoubleTrue   = packerDoubleTrue.toByteArray
    val unpackedDoubleTrue = Weaver.unweave[Boolean](packedDoubleTrue)
    unpackedDoubleTrue shouldBe true

    val packerDoubleFalse = MessagePack.newPacker()
    packerDoubleFalse.packDouble(0.0)
    val packedDoubleFalse   = packerDoubleFalse.toByteArray
    val unpackedDoubleFalse = Weaver.unweave[Boolean](packedDoubleFalse)
    unpackedDoubleFalse shouldBe false

    // From nil (should return default value for Boolean type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Boolean](packedNil)
    unpackedNil shouldBe false
  }

  test("Byte weaver basic operations") {
    val byteValues = Seq(0.toByte, 1.toByte, (-1).toByte, Byte.MaxValue, Byte.MinValue)

    for byteValue <- byteValues do
      val packed   = Weaver.weave(byteValue)
      val unpacked = Weaver.unweave[Byte](packed)
      unpacked shouldBe byteValue
  }

  test("Byte weaver from various types") {
    // From integer (within range)
    val packerInt = MessagePack.newPacker()
    packerInt.packLong(42L)
    val packedInt   = packerInt.toByteArray
    val unpackedInt = Weaver.unweave[Byte](packedInt)
    unpackedInt shouldBe 42.toByte

    // From double (whole number within range)
    val packerDouble = MessagePack.newPacker()
    packerDouble.packDouble(100.0)
    val packedDouble   = packerDouble.toByteArray
    val unpackedDouble = Weaver.unweave[Byte](packedDouble)
    unpackedDouble shouldBe 100.toByte

    // From string
    val packerStr = MessagePack.newPacker()
    packerStr.packString("50")
    val packedStr   = packerStr.toByteArray
    val unpackedStr = Weaver.unweave[Byte](packedStr)
    unpackedStr shouldBe 50.toByte

    // From boolean
    val packerBool = MessagePack.newPacker()
    packerBool.packBoolean(true)
    val packedBool   = packerBool.toByteArray
    val unpackedBool = Weaver.unweave[Byte](packedBool)
    unpackedBool shouldBe 1.toByte

    // From nil (should return default value for Byte type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Byte](packedNil)
    unpackedNil shouldBe 0.toByte
  }

  test("Byte weaver range validation") {
    // Test value outside byte range
    val packer = MessagePack.newPacker()
    packer.packInt(300) // Outside byte range
    val packed = packer.toByteArray

    val exception = intercept[IllegalArgumentException] {
      Weaver.unweave[Byte](packed)
    }
    exception.getMessage.contains("out of Byte range") shouldBe true

    // Test double value outside byte range
    val packerDouble = MessagePack.newPacker()
    packerDouble.packDouble(1000.0) // Outside byte range
    val packedDouble = packerDouble.toByteArray

    val exceptionDouble = intercept[IllegalArgumentException] {
      Weaver.unweave[Byte](packedDouble)
    }
    exceptionDouble.getMessage.contains("Cannot convert double") shouldBe true
  }

  test("Short weaver basic operations") {
    val shortValues = Seq(0.toShort, 1.toShort, (-1).toShort, Short.MaxValue, Short.MinValue)

    for shortValue <- shortValues do
      val packed   = Weaver.weave(shortValue)
      val unpacked = Weaver.unweave[Short](packed)
      unpacked shouldBe shortValue
  }

  test("Short weaver from various types") {
    // From integer (within range)
    val packerInt = MessagePack.newPacker()
    packerInt.packLong(1000L)
    val packedInt   = packerInt.toByteArray
    val unpackedInt = Weaver.unweave[Short](packedInt)
    unpackedInt shouldBe 1000.toShort

    // From double (whole number within range)
    val packerDouble = MessagePack.newPacker()
    packerDouble.packDouble(2000.0)
    val packedDouble   = packerDouble.toByteArray
    val unpackedDouble = Weaver.unweave[Short](packedDouble)
    unpackedDouble shouldBe 2000.toShort

    // From string
    val packerStr = MessagePack.newPacker()
    packerStr.packString("500")
    val packedStr   = packerStr.toByteArray
    val unpackedStr = Weaver.unweave[Short](packedStr)
    unpackedStr shouldBe 500.toShort

    // From boolean
    val packerBool = MessagePack.newPacker()
    packerBool.packBoolean(false)
    val packedBool   = packerBool.toByteArray
    val unpackedBool = Weaver.unweave[Short](packedBool)
    unpackedBool shouldBe 0.toShort

    // From nil (should return default value for Short type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Short](packedNil)
    unpackedNil shouldBe 0.toShort
  }

  test("Short weaver range validation") {
    // Test value outside short range
    val packer = MessagePack.newPacker()
    packer.packInt(50000) // Outside short range
    val packed = packer.toByteArray

    val exception = intercept[IllegalArgumentException] {
      Weaver.unweave[Short](packed)
    }
    exception.getMessage.contains("out of Short range") shouldBe true

    // Test double value outside short range
    val packerDouble = MessagePack.newPacker()
    packerDouble.packDouble(100000.0) // Outside short range
    val packedDouble = packerDouble.toByteArray

    val exceptionDouble = intercept[IllegalArgumentException] {
      Weaver.unweave[Short](packedDouble)
    }
    exceptionDouble.getMessage.contains("Cannot convert double") shouldBe true
  }

  test("Char weaver basic operations") {
    val charValues = Seq('a', 'Z', '0', '9', ' ', '\n', '\u0000', '\uFFFF')

    for charValue <- charValues do
      val packed   = Weaver.weave(charValue)
      val unpacked = Weaver.unweave[Char](packed)
      unpacked shouldBe charValue
  }

  test("Char weaver from string") {
    // Single character string
    val packer = MessagePack.newPacker()
    packer.packString("A")
    val packed   = packer.toByteArray
    val unpacked = Weaver.unweave[Char](packed)
    unpacked shouldBe 'A'
  }

  test("Char weaver from string - failure cases") {
    // Multi-character string should fail
    val packer = MessagePack.newPacker()
    packer.packString("AB")
    val packed = packer.toByteArray

    intercept[Exception] {
      Weaver.unweave[Char](packed)
    }
  }

  test("Char weaver from integer") {
    // Character code conversion
    val packer = MessagePack.newPacker()
    packer.packInt(65) // ASCII 'A'
    val packed   = packer.toByteArray
    val unpacked = Weaver.unweave[Char](packed)
    unpacked shouldBe 'A'
  }

  test("Char weaver range validation") {
    // Test integer value outside char range
    val packer = MessagePack.newPacker()
    packer.packLong(100000L) // Outside char range
    val packed = packer.toByteArray

    val exception = intercept[IllegalArgumentException] {
      Weaver.unweave[Char](packed)
    }
    exception.getMessage.contains("out of Char range") shouldBe true
  }

  test("Char weaver from nil") {
    // From nil (should return default value for Char type)
    val packerNil = MessagePack.newPacker()
    packerNil.packNil
    val packedNil   = packerNil.toByteArray
    val unpackedNil = Weaver.unweave[Char](packedNil)
    unpackedNil shouldBe '\u0000'
  }

  test("Primitive type error handling") {
    // Test unsupported conversions for each type
    val packer = MessagePack.newPacker()
    packer.packArrayHeader(1)
    packer.packInt(1)
    val packed = packer.toByteArray

    val primitiveTypes = Seq(
      () => Weaver.unweave[Long](packed),
      () => Weaver.unweave[Double](packed),
      () => Weaver.unweave[Float](packed),
      () => Weaver.unweave[Boolean](packed),
      () => Weaver.unweave[Byte](packed),
      () => Weaver.unweave[Short](packed),
      () => Weaver.unweave[Char](packed)
    )

    for unpackOperation <- primitiveTypes do
      intercept[Exception] {
        unpackOperation()
      }
  }

end PrimitiveWeaverTest
