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
package wvlet.uni.weaver

import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Arbitrary
import wvlet.uni.test.check.Gen

object WeaverPropertyTest:
  enum Level derives Weaver:
    case Low,
      Mid,
      High

  enum Shape derives Weaver:
    case Circle(r: Int)
    case Rect(w: Int, h: Int)
    case Empty

  case class Item(
      id: Long,
      name: String,
      active: Boolean,
      score: Double,
      note: Option[String],
      tags: List[String]
  ) derives Weaver

  case class Order(id: Int, items: List[Item], level: Level, shape: Shape, parent: Option[Item])
      derives Weaver

  given Arbitrary[Level] = Arbitrary.derived
  given Arbitrary[Shape] = Arbitrary.derived
  given Arbitrary[Item]  = Arbitrary.derived
  given Arbitrary[Order] = Arbitrary.derived

end WeaverPropertyTest

class WeaverPropertyTest extends UniTest:
  import WeaverPropertyTest.*

  // JSON has no representation for Infinity, so the JSON law runs on finite doubles only
  private def finite(d: Double): Double =
    if d.isInfinite then
      0.0
    else
      d

  private def finiteItem(i: Item): Item = i.copy(score = finite(i.score))

  private val genFiniteOrder: Gen[Order] = Arbitrary
    .arbitrary[Order]
    .map(o => o.copy(items = o.items.map(finiteItem), parent = o.parent.map(finiteItem)))

  test("unweave(weave(x)) restores any value") {
    forAll { (o: Order) =>
      Weaver.unweave[Order](Weaver.weave(o)) shouldBe o
    }
  }

  test("fromJson(toJson(x)) restores any value with finite doubles") {
    forAll(genFiniteOrder) { o =>
      Weaver.fromJson[Order](Weaver.toJson(o)) shouldBe o
    }
  }

  test("enums roundtrip through MessagePack and JSON") {
    forAll { (level: Level, shape: Shape) =>
      Weaver.unweave[Level](Weaver.weave(level)) shouldBe level
      Weaver.fromJson[Shape](Weaver.toJson(shape)) shouldBe shape
    }
  }

  test("Surface-based weaver encodes the same bytes as the derived weaver") {
    val surfaceWeaver = Weaver.fromSurface(Surface.of[Order]).asInstanceOf[Weaver[Order]]
    forAll { (o: Order) =>
      surfaceWeaver.toMsgPack(o).toSeq shouldBe Weaver.weave(o).toSeq
      surfaceWeaver.unweave(Weaver.weave(o)) shouldBe o
    }
  }

end WeaverPropertyTest
