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
import wvlet.uni.weaver.WeaverConfig

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
    l.toJSON shouldContain "\"version\":3"
    l.toJSON shouldContain "\"@type\":\"Split\""
    l.toJSON shouldContain "\"direction\":\"Column\""
    DockLayout.fromJSON(l.toJSON) shouldBe Some(l)
    Weaver.fromJson[DockLayout](Weaver.toJson(l)) shouldBe l
    summon[StorageCodec[DockLayout]].decode(summon[StorageCodec[DockLayout]].encode(l)) shouldBe
      Some(l)
  }

  test("an unreadable or newer save decodes to None") {
    DockLayout.fromJSON("not json") shouldBe None
    DockLayout.fromJSON("""{"root":{"@type":"Group","panels":["a"]}}""") shouldBe None
    DockLayout.fromJSON("""{"version":4,"root":{"@type":"Group","panels":["a"]}}""") shouldBe None
    DockLayout.fromJSON("""{"version":2,"root":{"@type":"Tabs"}}""") shouldBe None
    DockLayout.fromJSON("""{"version":2,"root":{"@type":"Group","panels":["a"]}}""") shouldBe
      Some(DockLayout(Group(Seq("a"), None)))
  }

  test("a version 1 save still loads") {
    DockLayout.fromJSON("""{"version":1,"root":{"type":"tabs"}}""") shouldBe None
    DockLayout.fromJSON("""{"version":1,"root":{"type":"group","panels":["a"]}}""") shouldBe
      Some(DockLayout(Group(Seq("a"), None)))
    val v1 =
      """{"version":1,"root":{"type":"split","direction":"row","children":[""" +
        """{"type":"group","panels":["a"],"active":"a"},""" +
        """{"type":"split","direction":"column","children":[""" +
        """{"type":"group","panels":["b","c"],"active":"c"},{"type":"group","panels":["d"]}],""" +
        """"sizes":[0.7,0.3]}],"sizes":[0.25,0.75]}}"""
    DockLayout.fromJSON(v1) shouldBe
      Some(
        DockLayout(
          Split(
            Row,
            Seq(
              Group(Seq("a"), Some("a")),
              Split(
                Column,
                Seq(Group(Seq("b", "c"), Some("c")), Group(Seq("d"), None)),
                Seq(0.7, 0.3)
              )
            ),
            Seq(0.25, 0.75)
          )
        )
      )
  }

  test("a version 1 save with bad sizes and unknown panels is fixed on load") {
    val v1 =
      """{"version":1,"root":{"type":"split","direction":"row","children":[""" +
        """{"type":"group","panels":["a","gone"],"active":"gone"},{"type":"group","panels":["b"]}],""" +
        """"sizes":[-1,2]}}"""
    val loaded = DockLayout.fromJSON(v1).map(DockModel.normalize(_, Set("a", "b")))
    loaded shouldBe Some(DockLayout(Split(Row, Seq(group("a"), group("b")), Seq(0.5, 0.5))))
    // Sizes missing entirely, written as integers
    DockLayout
      .fromJSON(
        """{"version":1,"root":{"type":"split","direction":"column","children":[""" +
          """{"type":"group","panels":["a"]},{"type":"group","panels":["b"]}]}}"""
      )
      .map(DockModel.normalize(_)) shouldBe
      Some(DockLayout(Split(Column, Seq(group("a"), group("b")), Seq(0.5, 0.5))))
    DockLayout
      .fromJSON(
        """{"version":1,"root":{"type":"split","direction":"row","children":[""" +
          """{"type":"group","panels":["a"]},{"type":"group","panels":["b"]}],"sizes":[1,3]}}"""
      )
      .map(DockModel.normalize(_)) shouldBe
      Some(DockLayout(Split(Row, Seq(group("a"), group("b")), Seq(0.25, 0.75))))
  }

  test("the saved shape ignores the caller's discriminator setting") {
    val l = DockLayout(row(group("a"), group("b")))
    Weaver.toJson(l, WeaverConfig().withDiscriminatorFieldName("kind")) shouldBe l.toJSON
  }

  test("the corner group is the top-right one, and top groups touch the top edge") {
    DockModel.cornerGroup(row(group("a"), column(group("b"), group("c")))) shouldBe List(1, 0)
    DockModel.cornerGroup(group("a")) shouldBe Nil
    DockModel.topGroups(row(group("a"), column(group("b"), group("c")))) shouldBe
      Set(List(0), List(1, 0))
  }

  // --- Floating groups ---

  private val box = DockBox(0.2, 0.1, 0.4, 0.5)

  test("float lifts a panel into a floating group on top, and dock puts it back") {
    val l = DockModel.float(DockLayout(row(group("a", "b"), group("c"))), "b", box)
    l shouldBe DockLayout(row(group("a"), group("c")), Seq(DockFloat.of(box, "b")))
    // The float's only tab stays where it is
    DockModel.float(l, "b", DockBox.default) shouldBe l
    // A second float goes on top
    val two = DockModel.float(l, "c", DockBox.default)
    two.root shouldBe group("a")
    two.floating.map(_.panels) shouldBe Seq(Seq("b"), Seq("c"))
    DockModel.groups(two) shouldBe Seq(Nil, List(-1), List(-2))

    DockModel.dock(l, "b") shouldBe DockLayout(row(Group(Seq("a", "b"), Some("b")), group("c")))
    // A panel in the grid isn't docked again
    DockModel.dock(l, "a") shouldBe l
  }

  test("tabs move between floats and the grid, and a float takes splits in its center") {
    val l = DockLayout(group("a", "b"), Seq(DockFloat.of(box, "c")))
    val f = DockModel.floatPath(0)
    DockModel.floatIndex(f) shouldBe Some(0)
    DockModel.floatIndex(List(0)) shouldBe None
    DockModel.groupOf(l, "c") shouldBe Some(f)

    DockModel.move(l, "a", f, 0) shouldBe
      DockLayout(group("b"), Seq(DockFloat(Seq("a", "c"), Some("a"), box)))
    DockModel.split(l, "a", f, DockSide.Left) shouldBe
      DockLayout(group("b"), Seq(DockFloat(Seq("c", "a"), Some("a"), box)))
    // Moving the float's last tab out removes the float
    DockModel.move(l, "c", Nil, 0) shouldBe DockLayout(Group(Seq("c", "a", "b"), Some("c")))
    DockModel.split(l, "c", Nil, DockSide.Right) shouldBe
      DockLayout(row(group("a", "b"), group("c")))
    DockModel.close(l, "c") shouldBe DockLayout(group("a", "b"))
    DockModel
      .activate(DockLayout(group("a"), Seq(DockFloat.of(box, "b", "c"))), "c")
      .floating shouldBe Seq(DockFloat(Seq("b", "c"), Some("c"), box))
    DockModel.open(l, "d", Some(f)).floating shouldBe Seq(DockFloat(Seq("c", "d"), Some("d"), box))
  }

  test("floats count in visible, panels, and the shape") {
    val l = DockLayout(group("a", "b"), Seq(DockFloat(Seq("c", "d"), Some("d"), box)))
    DockModel.visible(l) shouldBe Set("a", "d")
    l.visible shouldBe Set("a", "d")
    DockModel.panels(l) shouldBe Seq("a", "b", "c", "d")
    // Moving or switching tabs in a float doesn't change the shape
    DockModel.shape(DockModel.withBox(l, 0, DockBox.default)) shouldBe DockModel.shape(l)
    DockModel.shape(DockModel.activate(l, "c")) shouldBe DockModel.shape(l)
    DockModel.shape(DockModel.float(l, "a", box)) shouldNotBe DockModel.shape(l)
  }

  test("normalize drops empty and repeated floats, and keeps boxes inside the dock") {
    val l = DockLayout(
      group("a"),
      Seq(
        DockFloat.of(box, "a"),
        DockFloat.of(box, "zzz"),
        DockFloat(Seq("b"), Some("gone"), DockBox(0.9, -1, 0.5, 2)),
        DockFloat.of(DockBox(Double.NaN, 0, 0, Double.PositiveInfinity), "c")
      )
    )
    DockModel.normalize(l, known.contains, required = Seq("d")) shouldBe
      DockLayout(
        Group(Seq("a", "d"), Some("a")),
        Seq(
          DockFloat.of(DockBox(0.5, 0, 0.5, 1), "b"),
          DockFloat.of(DockBox(0.1, 0, 0.5, 0.5), "c")
        )
      )
    DockModel.fixBox(DockBox(0, 0, 0.001, 0.5)).width shouldBe DockModel.minFloatShare
    DockModel
      .withBox(DockLayout(group("a"), Seq(DockFloat.of(box, "b"))), 0, DockBox(0.8, 0.8, 0.4, 0.4))
      .floating
      .map(_.box) shouldBe Seq(DockBox(0.6, 0.6, 0.4, 0.4))
  }

  test("floats are saved in version 3, and older saves load without floats") {
    val l = DockLayout(group("a"), Seq(DockFloat(Seq("b", "c"), Some("c"), box)))
    l.toJSON shouldContain "\"floating\":["
    DockLayout.fromJSON(l.toJSON) shouldBe Some(l)
    Weaver.fromJson[DockLayout](Weaver.toJson(l)) shouldBe l
    // No floats, no key
    DockLayout(group("a")).toJSON shouldNotContain "floating"
    DockLayout.fromJSON("""{"version":3,"root":{"@type":"Group","panels":["a"]}}""") shouldBe
      Some(DockLayout(Group(Seq("a"), None)))
    DockLayout.fromJSON(
      """{"version":3,"root":{"@type":"Group","panels":["a"]},"floating":[{"panels":"x"}]}"""
    ) shouldBe None
  }

  private val three = DockLayout(row(group("a", "b"), group("c")), Seq(DockFloat.of(box, "d")))

  test("maximize names a grid group by one of its panels, and only it stays visible") {
    val m = three.maximize("b")
    m.maximized shouldBe Some("b")
    DockModel.maximizedGroup(m) shouldBe Some(List(0))
    m.visible shouldBe Set("a")
    m.restore shouldBe three
    // A float or a panel outside the layout can't be maximized
    three.maximize("d") shouldBe three
    three.maximize("zzz") shouldBe three
    // The structure is the same, so the dock doesn't rebuild on a toggle
    DockModel.shape(m) shouldBe DockModel.shape(three)
  }

  test("switching and reordering tabs in the maximized group keep it maximized") {
    val m = three.maximize("a")
    DockModel.activate(m, "b").maximized shouldBe Some("a")
    DockModel.move(m, "b", List(0), 0).maximized shouldBe Some("a")
    DockModel.withSizes(m, Nil, Seq(0.3, 0.7)).maximized shouldBe Some("a")
    // The group of a hidden panel, or a new one in it, keeps it too
    DockModel.close(m, "c").maximized shouldBe Some("a")
    // A group's only tab dropped back on its own tabs stays maximized
    val lone = DockLayout(row(group("a"), group("c"))).maximize("a")
    DockModel.move(lone, "a", List(0), 0).maximized shouldBe Some("a")
  }

  test("maximize needs another group to hide, and losing the last other group restores") {
    DockLayout.of("a", "b").maximize("a").maximized shouldBe None
    val m = DockLayout(row(group("a"), group("c"))).maximize("a")
    m.maximized shouldBe Some("a")
    // The hidden group's last tab closes (from code or an agent): one group is left
    DockModel.close(m, "c").maximized shouldBe None
  }

  test("anything that shows outside the maximized group restores first") {
    val m = three.maximize("a")
    DockModel.activate(m, "c").maximized shouldBe None
    DockModel.move(m, "a", List(1), 0).maximized shouldBe None
    DockModel.move(m, "c", List(0), 0).maximized shouldBe None
    DockModel.split(m, "a", List(0), DockSide.Right).maximized shouldBe None
    DockModel.float(m, "a").maximized shouldBe None
    DockModel.dock(m, "d").maximized shouldBe None
    DockModel
      .open(DockLayout(row(group("a"), group("c"))).maximize("a"), "b", Some(List(1)))
      .maximized shouldBe None
    // Opened into the maximized group, it stays
    DockModel
      .open(DockLayout(row(group("a"), group("c"))).maximize("a"), "b", Some(List(0)))
      .maximized shouldBe Some("a")
  }

  test("closing the panel that names the maximized group passes it on, and the last tab restores") {
    val m     = three.maximize("a")
    val after = DockModel.close(m, "a")
    after.maximized shouldBe Some("b")
    DockModel.maximizedGroup(after) shouldBe Some(List(0))
    DockModel.close(after, "b").maximized shouldBe None
    // A panel that normalize drops takes the maximized state with it
    DockModel.normalize(DockLayout(group("a"), maximized = Some("zzz"))).maximized shouldBe None
  }

  test("the maximized group is saved in version 3, and saves without it load restored") {
    val m = three.maximize("b")
    m.toJSON shouldContain "\"maximized\":\"b\""
    m.toJSON shouldContain "\"version\":3"
    DockLayout.fromJSON(m.toJSON) shouldBe Some(m)
    Weaver.fromJson[DockLayout](Weaver.toJson(m)) shouldBe m
    three.toJSON shouldNotContain "maximized"
    // Not a panel id: not maximized
    DockLayout.fromJSON(
      """{"version":3,"root":{"@type":"Group","panels":["a"]},"maximized":1}"""
    ) shouldBe Some(DockLayout(Group(Seq("a"), None)))
  }

end DockModelTest
