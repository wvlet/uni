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

import wvlet.uni.dom.StorageCodec
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import DockNode.{group, row, column, Group, Split}
import SplitDirection.{Row, Column}

class DockModelTest extends UniTest:

  private val known = Set("a", "b", "c", "d")

  private def norm(n: DockNode, required: Seq[String] = Seq.empty): DockNode =
    DockModel.normalize(DockLayout(n), known.contains, required).root

  private def near(xs: Seq[Double], ys: Seq[Double]): Boolean =
    xs.size == ys.size && xs.zip(ys).forall((x, y) => math.abs(x - y) < 1e-9)

  test("normalize drops unknown and repeated panels, and empty groups") {
    norm(row(group("a", "zzz"), group("zzz"), group("a", "b"))) shouldBe
      Split(Row, Seq(Group(Seq("a"), Some("a")), Group(Seq("b"), Some("b"))), Seq(0.5, 0.5))
  }

  test("normalize collapses a single-child split and fixes the active tab") {
    norm(row(Group(Seq("a", "b"), Some("gone")))) shouldBe Group(Seq("a", "b"), Some("a"))
  }

  test("normalize flattens a nested split in the same direction, keeping each share") {
    val n = Split(
      Row,
      Seq(group("a"), Split(Row, Seq(group("b"), group("c")), Seq(0.5, 0.5))),
      Seq(0.5, 0.5)
    )
    norm(n) match
      case Split(Row, cs, sizes) =>
        cs.map(DockModel.panels) shouldBe Seq(Seq("a"), Seq("b"), Seq("c"))
        near(sizes, Seq(0.5, 0.25, 0.25)) shouldBe true
      case other =>
        fail(s"Unexpected ${other}")
  }

  test("normalize resets invalid sizes and rescales the rest") {
    norm(Split(Column, Seq(group("a"), group("b")), Seq(1.0))) shouldBe
      column(group("a"), group("b"))
    norm(Split(Column, Seq(group("a"), group("b")), Seq(-1.0, 2.0))) shouldBe
      column(group("a"), group("b"))
    // Dropping a child gives its share to the others
    norm(Split(Row, Seq(group("a"), group("zzz"), group("b")), Seq(0.25, 0.5, 0.25))) match
      case Split(_, _, sizes) =>
        near(sizes, Seq(0.5, 0.5)) shouldBe true
      case other =>
        fail(s"Unexpected ${other}")
  }

  test("normalize adds missing required panels to the first group") {
    norm(row(group("a"), group("b")), required = Seq("b", "c")) shouldBe
      row(Group(Seq("a", "c"), Some("a")), group("b"))
    norm(group("zzz"), required = Seq("c")) shouldBe group("c")
    norm(group("zzz")) shouldBe DockLayout.empty.root
  }

  test("move puts a panel in another group, shows it, and removes an emptied group") {
    val l = DockLayout(row(group("a", "b"), group("c")))
    DockModel.move(l, "a", List(1), 0).root shouldBe
      row(Group(Seq("b"), Some("b")), Group(Seq("a", "c"), Some("a")))
    DockModel.move(l, "c", List(0), 1).root shouldBe Group(Seq("a", "c", "b"), Some("c"))
  }

  test("move within a group reorders its tabs") {
    val l = DockLayout(group("a", "b", "c"))
    DockModel.move(l, "a", Nil, 2).root shouldBe Group(Seq("b", "a", "c"), Some("a"))
    DockModel.move(l, "a", Nil, 3).root shouldBe Group(Seq("b", "c", "a"), Some("a"))
    DockModel.move(l, "c", Nil, 0).root shouldBe Group(Seq("c", "a", "b"), Some("c"))
  }

  test("split puts a panel beside a group on each side") {
    val l = DockLayout(group("a", "b"))
    DockModel.split(l, "b", Nil, DockSide.Bottom).root shouldBe column(group("a"), group("b"))
    DockModel.split(l, "b", Nil, DockSide.Top).root shouldBe column(group("b"), group("a"))
    DockModel.split(l, "b", Nil, DockSide.Left).root shouldBe row(group("b"), group("a"))
    DockModel.split(l, "b", Nil, DockSide.Right).root shouldBe row(group("a"), group("b"))
    DockModel.split(l, "b", Nil, DockSide.Center) shouldBe DockModel.move(l, "b", Nil, 2)
  }

  test("split in the parent's direction joins the parent's split") {
    val l = DockLayout(row(group("a", "c"), group("b")))
    DockModel.split(l, "c", List(1), DockSide.Right).root match
      case Split(Row, cs, sizes) =>
        cs.map(DockModel.panels) shouldBe Seq(Seq("a"), Seq("b"), Seq("c"))
        near(sizes, Seq(0.5, 0.25, 0.25)) shouldBe true
      case other =>
        fail(s"Unexpected ${other}")
  }

  test("splitting a group's only tab off itself does nothing") {
    val l = DockLayout(row(group("a"), group("b")))
    DockModel.split(l, "a", List(0), DockSide.Bottom) shouldBe l
  }

  test("moving a panel out of a split removes the split") {
    val l = DockLayout(column(group("a"), group("b")))
    DockModel.move(l, "b", List(0), 1).root shouldBe Group(Seq("a", "b"), Some("b"))
  }

  test("close shows the neighbor, and open adds a panel back") {
    val l      = DockLayout(Group(Seq("a", "b", "c"), Some("b")))
    val closed = DockModel.close(l, "b")
    closed.root shouldBe Group(Seq("a", "c"), Some("c"))
    DockModel.open(closed, "b").root shouldBe Group(Seq("a", "c", "b"), Some("b"))
    // Opening a panel that is in the layout switches to it
    DockModel.open(closed, "a").root shouldBe Group(Seq("a", "c"), Some("a"))
    val split = DockLayout(row(group("a"), group("c")))
    DockModel.open(split, "b", Some(List(1))).root shouldBe
      row(group("a"), Group(Seq("c", "b"), Some("b")))
    DockModel.open(DockModel.close(DockLayout.of("a"), "a"), "b").root shouldBe group("b")
  }

  test("resize moves one border and keeps both sides at the minimum") {
    val sizes = Seq(0.25, 0.25, 0.5)
    near(DockModel.resize(sizes, 0, 100, 1000, 120), Seq(0.35, 0.15, 0.5)) shouldBe true
    near(DockModel.resize(sizes, 0, 1000, 1000, 120), Seq(0.38, 0.12, 0.5)) shouldBe true
    near(DockModel.resize(sizes, 0, -1000, 1000, 120), Seq(0.12, 0.38, 0.5)) shouldBe true
    // Too small for two minimums: unchanged
    DockModel.resize(sizes, 0, 50, 400, 120) shouldBe sizes
    DockModel.resize(sizes, 2, 50, 1000, 120) shouldBe sizes
  }

  test("a layout round-trips through JSON with its version") {
    val l = DockLayout(
      row(
        group("a"),
        Split(Column, Seq(Group(Seq("b", "c"), Some("c")), group("d")), Seq(0.7, 0.3))
      )
    )
    l.toJSON shouldContain "\"version\":1"
    DockLayout.fromJSON(l.toJSON) shouldBe Some(l)
    Weaver.fromJson[DockLayout](Weaver.toJson(l)) shouldBe l
    summon[StorageCodec[DockLayout]].decode(summon[StorageCodec[DockLayout]].encode(l)) shouldBe
      Some(l)
  }

  test("an unreadable or newer save decodes to None") {
    DockLayout.fromJSON("not json") shouldBe None
    DockLayout.fromJSON("""{"root":{"type":"group","panels":["a"]}}""") shouldBe None
    DockLayout.fromJSON("""{"version":2,"root":{"type":"group","panels":["a"]}}""") shouldBe None
    DockLayout.fromJSON("""{"version":1,"root":{"type":"tabs"}}""") shouldBe None
    DockLayout.fromJSON("""{"version":1,"root":{"type":"group","panels":["a"]}}""") shouldBe
      Some(DockLayout(Group(Seq("a"), None)))
  }

  test("the corner group is the top-right one, and top groups touch the top edge") {
    DockModel.cornerGroup(row(group("a"), column(group("b"), group("c")))) shouldBe List(1, 0)
    DockModel.cornerGroup(group("a")) shouldBe Nil
    DockModel.topGroups(row(group("a"), column(group("b"), group("c")))) shouldBe
      Set(List(0), List(1, 0))
  }

end DockModelTest
