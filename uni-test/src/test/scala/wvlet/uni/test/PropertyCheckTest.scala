/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.test

import wvlet.uni.test.check.Arbitrary
import wvlet.uni.test.check.Gen
import wvlet.uni.test.check.PropertyConfig
import wvlet.uni.test.check.Shrink

/**
  * Self-test for the built-in property-based testing support.
  */
class PropertyCheckTest extends UniTest:

  // Reduce sample count to keep the suite snappy.
  override protected def propertyConfig: PropertyConfig = PropertyConfig
    .default
    .withMinSuccessful(50)

  test("forAll with implicit Arbitrary") {
    forAll { (a: Int, b: Int) =>
      (a + b) shouldBe (b + a)
    }
  }

  test("forAll with explicit Gen") {
    forAll(Gen.chooseNum(1, 100)) { (n: Int) =>
      (n >= 1) shouldBe true
      (n <= 100) shouldBe true
    }
  }

  test("chooseNum is inclusive on both ends") {
    forAll(Gen.chooseNum(7, 7)) { (n: Int) =>
      n shouldBe 7
    }
  }

  test("chooseNum[Long] keeps wide overflowing ranges in-bounds") {
    // `max - min + 1` overflows when min=-10 and max=Long.MaxValue, so the runner must use
    // rejection sampling rather than returning an unbounded `nextLong()`.
    forAll(Gen.chooseNum[Long](-10L, Long.MaxValue)) { (l: Long) =>
      (l >= -10L) shouldBe true
      (l <= Long.MaxValue) shouldBe true
    }
  }

  test("chooseNum for Byte/Short/Long") {
    forAll(Gen.chooseNum[Byte](-10, 10)) { (b: Byte) =>
      (b >= -10) shouldBe true
      (b <= 10) shouldBe true
    }
    forAll(Gen.chooseNum[Short](-1000, 1000)) { (s: Short) =>
      (s >= -1000) shouldBe true
      (s <= 1000) shouldBe true
    }
    forAll(Gen.chooseNum[Long](Long.MinValue, Long.MaxValue)) { (l: Long) =>
      (l >= Long.MinValue) shouldBe true
    }
  }

  test("posNum always produces positive values") {
    forAll(Gen.posNum[Int]) { (n: Int) =>
      (n > 0) shouldBe true
    }
    forAll(Gen.posNum[Long]) { (n: Long) =>
      (n > 0) shouldBe true
    }
  }

  test("Arbitrary.arbitrary[T] summons the default generator") {
    forAll(Arbitrary.arbitrary[String]) { (s: String) =>
      (s.length >= 0) shouldBe true
    }
  }

  test("float/double generators never emit NaN") {
    forAll { (f: Float) =>
      java.lang.Float.isNaN(f) shouldBe false
    }
    forAll { (d: Double) =>
      java.lang.Double.isNaN(d) shouldBe false
    }
  }

  test("Gen.map composes") {
    forAll(Gen.chooseNum(0, 100).map(_ * 2)) { (n: Int) =>
      (n % 2 == 0) shouldBe true
    }
  }

  test("Gen.listOfN produces the requested length") {
    forAll(Gen.listOfN(5, Gen.chooseNum(0, 10))) { (xs: List[Int]) =>
      xs.size shouldBe 5
    }
  }

  test("Gen.frequency respects weights (smoke)") {
    val g      = Gen.frequency(1 -> Gen.const("a"), 9 -> Gen.const("b"))
    var aCount = 0
    var bCount = 0
    forAll(g) { (s: String) =>
      if s == "a" then
        aCount += 1
      else
        bCount += 1
    }
    // With 50 samples and 1:9 ratio, "b" should dominate. Allow generous slack.
    (bCount > aCount) shouldBe true
  }

  test("Gen.listOf grows with size") {
    forAll(Gen.listOf(Gen.chooseNum(0, 10))) { (xs: List[Int]) =>
      (xs.size >= 0) shouldBe true
      (xs.size <= propertyConfig.maxSize) shouldBe true
    }
  }

  test("Gen.option yields Some and None") {
    var sawSome = false
    var sawNone = false
    forAll(Gen.option(Gen.const(42))) { (o: Option[Int]) =>
      o match
        case Some(42) =>
          sawSome = true
        case None =>
          sawNone = true
        case other =>
          fail(s"unexpected ${other}")
    }
    sawSome shouldBe true
    sawNone shouldBe true
  }

  test("Gen.mapOf produces a Map") {
    forAll(Gen.mapOf(Gen.chooseNum(0, 100), Gen.alphaStr)) { (m: Map[Int, String]) =>
      (m.size >= 0) shouldBe true
    }
  }

  test("Gen.alphaNumStr contains only alphanumerics") {
    forAll(Gen.alphaNumStr) { (s: String) =>
      s.forall(c => c.isLetterOrDigit) shouldBe true
    }
  }

  test("Gen.identifier starts with a letter") {
    forAll(Gen.identifier) { (s: String) =>
      s.nonEmpty shouldBe true
      s.head.isLetter shouldBe true
      s.tail.forall(_.isLetterOrDigit) shouldBe true
    }
  }

  test("Gen.sized/resize") {
    forAll(Gen.resize(3, Gen.listOf(Gen.chooseNum(0, 10)))) { (xs: List[Int]) =>
      (xs.size <= 3) shouldBe true
    }
  }

  test("failing property reports the counter-example and seed") {
    val ex = intercept[AssertionFailure] {
      forAll(Gen.chooseNum(1, 1)) { (n: Int) =>
        n shouldBe 2
      }
    }
    ex.getMessage shouldContain "1"
    ex.getMessage shouldContain "seed="
  }

  test("==> discards when precondition is false") {
    // Without discard this would fail the moment we draw 0; with discard it simply skips 0.
    forAll { (n: Int) =>
      (n != 0) ==> {
        (n * 0 == 0) shouldBe true
      }
    }
  }

  test("implies is the English alias of ==>") {
    forAll { (n: Int) =>
      (n != 0) implies {
        (n * 0 == 0) shouldBe true
      }
    }
  }

  test("too many discards surfaces an error") {
    val ex = intercept[AssertionFailure] {
      forAll(Gen.const(0)) { (n: Int) =>
        (n != 0) ==> {
          // Never reached
          1 shouldBe 1
        }
      }
    }
    ex.getMessage shouldContain "discard"
  }

  test("seeded runs are reproducible") {
    val seededConfig = PropertyConfig.default.withMinSuccessful(10).withSeed(42L)
    var firstValues  = List.empty[Int]
    var secondValues = List.empty[Int]

    val runner =
      new PropertyCheck:
        override protected def propertyConfig = seededConfig
    runner.forAll(Arbitrary.arbitrary[Int]) { (n: Int) =>
      firstValues = n :: firstValues
    }
    runner.forAll(Arbitrary.arbitrary[Int]) { (n: Int) =>
      secondValues = n :: secondValues
    }
    firstValues shouldBe secondValues
  }

  test("shrinking produces a minimal counter-example") {
    val ex = intercept[AssertionFailure] {
      forAll { (n: Int) =>
        // Any non-zero value fails.
        (n == 0) shouldBe true
      }
    }
    // The shrinker should drive the counter-example toward 0-adjacent values; any reasonable
    // shrink stops at a small magnitude. Verify the reported input is much smaller than Int.MaxValue.
    val extracted = """property input: (-?\d+)"""
      .r
      .findFirstMatchIn(ex.getMessage)
      .map(_.group(1).toLong)
    extracted shouldMatch {
      case Some(v) if math.abs(v) <= 1L =>
    }
  }

  test("shrinking reduces lists to empty or small when empty list fails") {
    val ex = intercept[AssertionFailure] {
      forAll { (xs: List[Int]) =>
        // Any list fails the property.
        xs.size < 0 shouldBe true
      }
    }
    ex.getMessage shouldContain "property input: List()"
  }

  test("classify records label counts") {
    forAll(Gen.chooseNum(-100, 100)) { (n: Int) =>
      classify(n >= 0, "non-negative", "negative")
      (n * 1 == n) shouldBe true
    }
    // No assertion on log output itself (captured via println); we verify the run completes.
  }

  test("derived Arbitrary for a case class") {
    case class Point(x: Int, y: Int)
    given Arbitrary[Point] = Arbitrary.derived

    forAll { (p: Point) =>
      // Both components should be Ints (trivially true via the type)
      (p.x == p.x) shouldBe true
      (p.y == p.y) shouldBe true
    }
  }

  test("derived Arbitrary for a sum type") {
    sealed trait Shape derives Arbitrary
    case class Circle(r: Int)                   extends Shape
    case class Rect(w: Int, h: Int)             extends Shape
    case class Triangle(a: Int, b: Int, c: Int) extends Shape

    forAll { (s: Shape) =>
      s shouldMatch {
        case _: Circle   =>
        case _: Rect     =>
        case _: Triangle =>
      }
    }
  }

end PropertyCheckTest
