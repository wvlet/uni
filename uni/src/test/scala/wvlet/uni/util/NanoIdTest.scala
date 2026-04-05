package wvlet.uni.util

import wvlet.uni.test.UniTest

class NanoIdTest extends UniTest:

  test("generate default NanoId") {
    val id = NanoId.generate()
    id.length shouldBe 21
    assert(id.forall(c => NanoId.DefaultAlphabet.contains(c)))
  }

  test("generate with custom size") {
    val id = NanoId.generate(10)
    id.length shouldBe 10
    val idLong = NanoId.generate(100)
    idLong.length shouldBe 100
  }

  test("generate with custom alphabet and size") {
    val alphabet = "abc"
    val id       = NanoId.generate(alphabet, 30)
    id.length shouldBe 30
    assert(id.forall(c => alphabet.contains(c)))
  }

  test("generated IDs are unique") {
    val ids = (1 to 1000).map(_ => NanoId.generate()).toSet
    // With 21 chars from 64 alphabet, collisions are astronomically unlikely
    ids.size shouldBe 1000
  }

  test("reject empty alphabet") {
    intercept[IllegalArgumentException] {
      NanoId.generate("", 10)
    }
  }

  test("reject alphabet longer than 255") {
    val longAlphabet = (0 until 256).map(i => (i + 256).toChar).mkString
    intercept[IllegalArgumentException] {
      NanoId.generate(longAlphabet, 10)
    }
  }

  test("reject non-positive size") {
    intercept[IllegalArgumentException] {
      NanoId.generate(0)
    }
    intercept[IllegalArgumentException] {
      NanoId.generate(-1)
    }
  }

  test("single character alphabet") {
    val id = NanoId.generate("x", 10)
    id shouldBe "xxxxxxxxxx"
  }

  test("generate with custom random source") {
    val random = new scala.util.Random(42)
    val id1    = NanoId.generate(NanoId.DefaultAlphabet, 21, new scala.util.Random(42))
    val id2    = NanoId.generate(NanoId.DefaultAlphabet, 21, new scala.util.Random(42))
    id1 shouldBe id2
  }

end NanoIdTest
