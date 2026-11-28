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
package wvlet.uni.test.result

import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.FuzzConfig

case class Sale(id: Long, country: String, amount: Double)
case class Point(x: Int, y: Int)

class ResultCompareTest extends UniTest:

  private def same(
      a: Iterable[Any],
      e: Iterable[Any],
      c: ResultCompareConfig = ResultCompareConfig()
  ) = ResultCompare.same(a, e, c)

  test("bags match in any order with the same multiplicities") {
    Seq(3, 1, 2, 1) shouldBeInAnyOrder Seq(1, 1, 2, 3)
    same(Seq(1, 2), Seq(1, 2, 2)) shouldBe false
    same(Seq(1, 1, 2), Seq(1, 2, 2)) shouldBe false
    same(Seq.empty, Seq.empty) shouldBe true
  }

  test("case class rows match in any order") {
    val sales = Seq(Sale(1, "JP", 10.5), Sale(2, "US", 3.0), Sale(3, "JP", 0.1 + 0.2))
    sales.reverse shouldBeInAnyOrder
      Seq(Sale(1, "JP", 10.5), Sale(2, "US", 3.0), Sale(3, "JP", 0.3))
    same(Seq(Point(1, 2)), Seq(Point(2, 1))) shouldBe false
  }

  test("map rows match with numbers of other types") {
    val rows = Seq(Map("id" -> 2L, "name" -> "b"), Map("id" -> 1L, "name" -> null))
    rows shouldBeInAnyOrder
      Seq(Map("id" -> 1, "name" -> None), Map("id" -> BigDecimal(2), "name" -> "b"))
  }

  test("NULL equals NULL") {
    ResultCompare.valueEquals(null, null) shouldBe true
    ResultCompare.valueEquals(None, null) shouldBe true
    ResultCompare.valueEquals(Some(1), 1L) shouldBe true
    ResultCompare.valueEquals(null, 0) shouldBe false
    ResultCompare.valueEquals("", null) shouldBe false
    Seq(Seq(1, null), Seq(null, null)) shouldBeInAnyOrder Seq(Seq(null, null), Seq(1, None))
  }

  test("numbers compare by value across types") {
    ResultCompare.valueEquals(1, 1L) shouldBe true
    ResultCompare.valueEquals(1L, BigDecimal("1.00")) shouldBe true
    ResultCompare.valueEquals(BigInt(5), java.math.BigDecimal("5")) shouldBe true
    ResultCompare.valueEquals(1.5, BigDecimal("1.5")) shouldBe true
    // A Float compares through its decimal form. Scala.js has no separate Float at run time, so
    // 0.1f is the double 0.10000000149... there
    if !isScalaJS then
      ResultCompare.valueEquals(0.1f, 0.1) shouldBe true
    ResultCompare.valueEquals(1, 2L) shouldBe false
    ResultCompare.valueEquals(1, "1") shouldBe false
    ResultCompare.valueEquals(BigDecimal("0.1000000001"), BigDecimal("0.1")) shouldBe false
  }

  test("floating numbers compare within a tolerance") {
    val sum = (1 to 10).map(_ => 0.1).sum
    ResultCompare.valueEquals(sum, 1.0) shouldBe true
    ResultCompare.valueEquals(1.0, 1.0001) shouldBe false
    ResultCompare.valueEquals(1.0, 1.0001, ResultCompareConfig().withRelTolerance(1e-3)) shouldBe
      true
    ResultCompare.valueEquals(sum, 1.0, ResultCompareConfig().noFloatTolerance) shouldBe false
    ResultCompare.valueEquals(1e-13, 0.0) shouldBe true
    ResultCompare.valueEquals(-0.0, 0.0, ResultCompareConfig().noFloatTolerance) shouldBe true
  }

  test("NaN equals NaN, and infinities only themselves") {
    ResultCompare.valueEquals(Double.NaN, Double.NaN) shouldBe true
    ResultCompare.valueEquals(Float.NaN, Double.NaN) shouldBe true
    ResultCompare.valueEquals(Double.NaN, 0.0) shouldBe false
    ResultCompare.valueEquals(Double.PositiveInfinity, Double.PositiveInfinity) shouldBe true
    ResultCompare.valueEquals(Double.PositiveInfinity, Double.MaxValue) shouldBe false
    ResultCompare.valueEquals(Double.NegativeInfinity, Double.PositiveInfinity) shouldBe false
    Seq(Double.NaN, 1.0) shouldBeInAnyOrder Seq(1.0, Double.NaN)
  }

  test("rows that match only within tolerance pair up even when tolerance is not transitive") {
    // Each actual value is close to both expected ones, but a greedy pairing that takes the
    // first candidate can leave 1.0 + 2e-9 without a partner
    val a = Seq(Seq("x", 1.0 + 1e-9), Seq("x", 1.0 - 1e-9))
    val e = Seq(Seq("x", 1.0), Seq("x", 1.0 - 1.5e-9))
    val c = ResultCompareConfig().withRelTolerance(0).withAbsTolerance(1.2e-9)
    // 1+1e-9 matches only 1.0; 1-1e-9 matches both
    ResultCompare.same(a, e, c) shouldBe true
    ResultCompare.same(a.reverse, e, c) shouldBe true
  }

  test("nested values compare element-wise") {
    ResultCompare.valueEquals(Array(1, 2), Seq(1L, 2L)) shouldBe true
    ResultCompare.valueEquals(Seq(Seq(1.0)), List(Vector(1))) shouldBe true
    ResultCompare.valueEquals(Map("a" -> Seq(1)), Map("a" -> List(1L))) shouldBe true
    ResultCompare.valueEquals(Map("a" -> 1), Map("a" -> 1, "b" -> 2)) shouldBe false
    ResultCompare.valueEquals(Set(1, 2), Set(2L, 1L)) shouldBe true
    ResultCompare.valueEquals((1, "a"), (1L, "a")) shouldBe true
    ResultCompare.valueEquals(Seq(1, 2), Seq(2, 1)) shouldBe false
    Seq(Array[Byte](1, 2), Array[Byte](3)) shouldBeInAnyOrder Seq(Array[Byte](3), Array[Byte](1, 2))
  }

  test("ordered results must match row by row") {
    val c = ResultCompareConfig().withOrdered
    same(Seq(1, 2, 3), Seq(1, 2, 3), c) shouldBe true
    ResultCompare.diff(Seq(1, 3, 2), Seq(1, 2, 3), c) shouldBe
      Some("Results differ at row 1: expected <2> but got <3>")
    ResultCompare.diff(Seq(1), Seq(1, 2), c) shouldBe
      Some("Expected 2 rows but got 1: missing row 1 <2>")
    ResultCompare.diff(Seq(1, 2), Seq(1), c) shouldBe
      Some("Expected 1 rows but got 2: unexpected row 1 <2>")
  }

  test("rows ordered by a key compare ties as a bag") {
    val c = ResultCompareConfig().withOrderedBy[Sale](_.country)
    val e = Seq(Sale(1, "JP", 1), Sale(2, "JP", 2), Sale(3, "US", 3))
    same(Seq(Sale(2, "JP", 2), Sale(1, "JP", 1), Sale(3, "US", 3)), e, c) shouldBe true
    same(Seq(Sale(3, "US", 3), Sale(1, "JP", 1), Sale(2, "JP", 2)), e, c) shouldBe false
    ResultCompare.diff(Seq(Sale(1, "JP", 1), Sale(3, "JP", 2), Sale(3, "US", 3)), e, c) shouldMatch {
      case Some(msg) if msg.startsWith("Rows with sort key <\"JP\">") =>
    }
    ResultCompare.diff(Seq(Sale(1, "JP", 1), Sale(2, "JP", 2)), e, c) shouldBe
      Some("Results differ: missing rows with sort key <\"US\">")
  }

  test("row count mode compares only sizes") {
    val c = ResultCompareConfig().withRowCountOnly
    same(Seq(1, 2), Seq(3, 4), c) shouldBe true
    ResultCompare.diff(Seq(1), Seq(3, 4), c) shouldBe Some("Expected 2 rows but got 1")
  }

  test("normalize maps values before comparison") {
    // An engine that returns dates as day counts, against one that returns strings
    val c = ResultCompareConfig().withNormalize {
      case d: Int if d > 10000 =>
        java.time.LocalDate.ofEpochDay(d.toLong).toString
      case other =>
        other
    }
    same(Seq(Seq(1, 19723)), Seq(Seq(1, "2024-01-01")), c) shouldBe true
    same(Seq(Seq(1, 19723)), Seq(Seq(1, "2024-01-01"))) shouldBe false
  }

  test("a difference lists missing and unexpected rows") {
    ResultCompare.diff(Seq(1, 2, 4), Seq(1, 2, 3, 3)) shouldBe
      Some("""Results differ: expected 4 rows but got 3, 2 missing and 1 unexpected
          |  missing (2):
          |    3
          |    3
          |  unexpected (1):
          |    4""".stripMargin)
    ResultCompare.diff(
      (1 to 5).map(i => s"r${i}"),
      Seq.empty,
      ResultCompareConfig().withMaxShownRows(2)
    ) shouldBe Some("""Results differ: expected 0 rows but got 5, 0 missing and 5 unexpected
          |  unexpected (5):
          |    "r1"
          |    "r2"
          |    ... 3 more""".stripMargin)
  }

  test("a mismatch is a ResultMismatch") {
    val e = intercept[ResultMismatch] {
      Seq(1) shouldBeInAnyOrder Seq(2)
    }
    e.getMessage shouldContain "1 missing and 1 unexpected"
  }

  test("a shuffled result always matches") {
    val rows = Gen.listOf(
      Gen.oneOfGen(
        Gen.chooseNum(-3, 3).map(i => Seq(i, i * 0.1)),
        Gen.const(Seq(null, Double.NaN)),
        Gen.alphaStr.map(s => Seq(s, s.length.toLong))
      )
    )
    fuzzGen(
      "result-shuffle",
      rows.zip(Gen.chooseNum(0L, Long.MaxValue)),
      FuzzConfig().withIterations(300)
    ) { (rs, seed) =>
      val shuffled = scala
        .util
        .Random(seed)
        .shuffle(rs)
        .map(
          _.map {
            case i: Int =>
              i.toLong
            case d: Double =>
              d + d * 1e-12
            case other =>
              other
          }
        )
      shuffled shouldBeInAnyOrder rs
      if rs.nonEmpty then
        ResultCompare.same(shuffled.tail, rs) shouldBe false
    }
  }

end ResultCompareTest
