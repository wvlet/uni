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
import wvlet.uni.test.check.Shrink
import wvlet.uni.test.fuzz.FuzzConfig
import wvlet.uni.test.fuzz.Reduce

object ShrinkTreeTest:
  import GenRecursiveTest.Expr
  import GenRecursiveTest.Expr.*

  val shrinkExpr: Shrink[Expr] = Shrink.tree[Expr](
    children = {
      case Num(_) =>
        Nil
      case Neg(e) =>
        List(e)
      case Add(args) =>
        args
    },
    withChildren = {
      case (Neg(_), Seq(e)) =>
        Some(Neg(e))
      case (Add(_), args) =>
        Some(Add(args.toList))
      case _ =>
        None
    },
    local = {
      case Num(v) =>
        Shrink.shrink(v).map(Num(_))
      case _ =>
        LazyList.empty
    }
  )

  /** The planted bug: negating a non-empty sum */
  def hasNegatedSum(e: Expr): Boolean =
    e match
      case Num(_) =>
        false
      case Neg(Add(args)) if args.nonEmpty =>
        true
      case Neg(x) =>
        hasNegatedSum(x)
      case Add(args) =>
        args.exists(hasNegatedSum)

end ShrinkTreeTest

class ShrinkTreeTest extends UniTest:
  import GenRecursiveTest.Expr
  import GenRecursiveTest.Expr.*
  import ShrinkTreeTest.*

  test("hoists a failing subtree out of its ancestors") {
    val deep = Add(
      List(Num(3), Neg(Neg(Add(List(Num(5), Neg(Add(List(Num(7), Neg(Num(2))))), Num(9))))))
    )
    Reduce.shrink(deep, hasNegatedSum, 10000)(using shrinkExpr) shouldBe Neg(Add(List(Num(0))))
  }

  test("keeps a node whose children cannot be dropped") {
    // Neg needs one child, so the shrinker never offers Neg with none
    shrinkExpr.shrink(Neg(Num(4))).toList shouldBe
      List(Num(4), Neg(Num(0)), Neg(Num(2)), Neg(Num(3)))
  }

  test("stops within the step budget") {
    var calls = 0
    val deep  = Neg(Add(List.tabulate(20)(i => Neg(Add(List(Num(i)))))))
    Reduce.shrink(
      deep,
      { e =>
        calls += 1
        hasNegatedSum(e)
      },
      5
    )(using shrinkExpr)
    calls shouldBe 5
  }

  test("fuzzGen reports the shrunk value next to the replayable one") {
    // The fixed wrapper comes from the generator, so byte reduction cannot remove it
    val wrapped = GenRecursiveTest.expr.map(e => Add(List(Num(1), Neg(Neg(e)))))
    val config  = FuzzConfig().withSeed(42L).withCrashDir("target/fuzz-test").noEnvOverrides
    val ex      = intercept[AssertionFailure] {
      fuzzGen("tree-shrink", wrapped, config.withIterations(5000), shrink = shrinkExpr) { e =>
        if hasNegatedSum(e) then
          throw IllegalStateException("negated sum")
      }
    }
    ex.getMessage shouldContain "value: Add(List(Num(1), Neg(Neg("
    ex.getMessage shouldContain "shrunk value: Neg(Add(List(Num(0))))"
    ex.getMessage shouldContain "shrunk value fails with: java.lang.IllegalStateException"
  }

end ShrinkTreeTest
