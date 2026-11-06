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
package wvlet.uni.ui.base

import wvlet.uni.test.UniTest

class AnchoredTest extends UniTest:
  private val viewport = Rect(0, 0, 800, 600)
  private val anchor   = Rect(100, 100, 80, 30)

  test("places below the anchor, aligned to its start"):
    Anchored.place(anchor, 200, 100, viewport, Side.Bottom, Align.Start, offset = 6) shouldBe
      Placement(100, 136, Side.Bottom)

  test("aligns to the center and the end"):
    Anchored.place(anchor, 40, 20, viewport, Side.Bottom, Align.Center).x shouldBe 120
    Anchored.place(anchor, 40, 20, viewport, Side.Bottom, Align.End).x shouldBe 140

  test("flips to the opposite side when there's no room"):
    val low = Rect(100, 560, 80, 30)
    val p   = Anchored.place(low, 200, 100, viewport, Side.Bottom, offset = 6)
    p.side shouldBe Side.Top
    p.y shouldBe 454
    // Right side has no room near the right edge, so it goes left
    val edge = Rect(700, 100, 50, 30)
    Anchored.place(edge, 120, 40, viewport, Side.Right, offset = 6).side shouldBe Side.Left

  test("takes the roomier side when neither side fits"):
    val p = Anchored.place(Rect(100, 400, 80, 30), 200, 500, viewport, Side.Bottom)
    p.side shouldBe Side.Top

  test("shifts along the edge to stay inside the viewport"):
    val nearRight = Rect(760, 100, 30, 30)
    val p         = Anchored.place(nearRight, 200, 100, viewport, Side.Bottom, padding = 8)
    p.x shouldBe 592
    val nearLeft = Rect(2, 100, 30, 30)
    Anchored.place(nearLeft, 200, 100, viewport, Side.Bottom, Align.Center, padding = 8).x shouldBe
      8

  test("a point anchor places a context menu at the pointer"):
    Anchored.place(Rect.point(300, 200), 160, 120, viewport, Side.Bottom, offset = 0) shouldBe
      Placement(300, 200, Side.Bottom)

end AnchoredTest
