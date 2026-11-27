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

import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.FuzzConfig

object GenRecursiveTest:
  enum Expr:
    case Num(v: Int)
    case Neg(e: Expr)
    case Add(args: List[Expr])

    def depth: Int =
      this match
        case Num(_) =>
          0
        case Neg(e) =>
          1 + e.depth
        case Add(args) =>
          1 + args.map(_.depth).maxOption.getOrElse(0)

    def branches: Int =
      this match
        case Num(_) =>
          0
        case Neg(e) =>
          1 + e.branches
        case Add(args) =>
          1 + args.map(_.branches).sum

    def eval: Int =
      this match
        case Num(v) =>
          v
        case Neg(e) =>
          -e.eval
        case Add(args) =>
          args.map(_.eval).sum

  end Expr

  val num: Gen[Expr]  = Gen.chooseNum(0, 9).map(Expr.Num(_))
  val expr: Gen[Expr] =
    Gen.recursive(num) { child =>
      Gen.oneOfGen(child.map(Expr.Neg(_)), Gen.listOf(child).map(Expr.Add(_)))
    }

end GenRecursiveTest

class GenRecursiveTest extends UniTest:
  import GenRecursiveTest.*

  test("recursive values stay within the depth and size limits") {
    forAll(expr) { e =>
      (e.depth <= 8) shouldBe true
      (e.branches <= 100) shouldBe true
    }
    val shallow = Gen.recursive(num, maxDepth = 2)(child => child.map(Expr.Neg(_)))
    forAll(shallow) { e =>
      (e.depth <= 2) shouldBe true
    }
  }

  test("a large size still gives finite values") {
    forAll(Gen.resize(10000, expr)) { e =>
      (e.branches <= 10000) shouldBe true
    }
  }

  test("generates both leaves and nested branches") {
    val depths = (0 until 300).flatMap(i => FuzzCheck.generate(expr, bytesOf(i), 32)).map(_.depth)
    depths.contains(0) shouldBe true
    (depths.max >= 3) shouldBe true
  }

  test("zero bytes give a leaf, so reduced fuzz inputs shrink toward leaves") {
    FuzzCheck.generate(expr, Array.emptyByteArray, 32) shouldBe Some(Expr.Num(0))
  }

  test("fuzzGen reduces a failing expression") {
    val ex = intercept[AssertionFailure] {
      fuzzGen("expr-eval", expr, FuzzConfig().withSeed(7L).withIterations(5000).noEnvOverrides) {
        e =>
          // A planted bug: negating a sum is wrong
          e match
            case Expr.Neg(Expr.Add(args)) if args.nonEmpty =>
              throw IllegalStateException("negated sum")
            case _ =>
              e.eval
      }
    }
    ex.getMessage shouldContain "value: Neg(Add(List("
  }

  test("lzy lets generators refer to each other") {
    lazy val a: Gen[Expr] = Gen.frequency(3 -> num, 1 -> Gen.lzy(b))
    lazy val b: Gen[Expr] = a.map(Expr.Neg(_))
    forAll(a) { e =>
      (e.eval.abs <= 9) shouldBe true
    }
  }

  private def bytesOf(i: Int): Array[Byte] =
    Array.tabulate[Byte](64)(j => ((i * 31 + j * 17) ^ (i >> 2)).toByte)

end GenRecursiveTest
