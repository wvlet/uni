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

import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.ui.{ChangeCause, ChangeDetails, ChangeReason, ChangeSource}

import scala.collection.mutable.ArrayBuffer

class BehaviorModelTest extends UniTest:

  test("typeahead adds up keys typed in quick succession") {
    var clock = 0.0
    val t     = Typeahead(timeoutMillis = 500, now = () => clock)
    val words = Seq("Apple", "Cabbage", "Car", "Cat")
    t.onKey("c", words, -1) shouldBe Some(1)
    clock += 100
    t.onKey("a", words, 1) shouldBe Some(1)
    clock += 100
    t.onKey("t", words, 1) shouldBe Some(3)
    t.query shouldBe "cat"
  }

  test("typeahead starts a new search after a pause, and a repeated letter cycles") {
    var clock = 0.0
    val t     = Typeahead(timeoutMillis = 500, now = () => clock)
    val words = Seq("Apple", "Cabbage", "Car", "Cat")
    t.onKey("c", words, -1) shouldBe Some(1)
    clock += 100
    t.onKey("c", words, 1) shouldBe Some(2)
    clock += 1000
    t.onKey("a", words, 2) shouldBe Some(0)
    t.onKey("Enter", words, 0) shouldBe None
    clock += 1000
    // A space alone is left to the component; inside a search it is part of the label
    t.onKey(" ", words, 0) shouldBe None
    val cities = Seq("New Delhi", "New York", "Newark")
    Seq("n", "e", "w", " ", "y").map(k => t.onKey(k, cities, -1)).last shouldBe Some(1)
  }

  test("input history recalls entries and brings back the draft") {
    val h = InputHistory(capacity = 3)
    Seq("one", "two", "two", " ", "three").foreach(h.add)
    h.entries shouldBe Seq("one", "two", "three")
    h.next() shouldBe None
    h.previous("drafting") shouldBe Some("three")
    h.recalling shouldBe true
    h.previous("three") shouldBe Some("two")
    h.previous("two") shouldBe Some("one")
    h.previous("one") shouldBe None
    h.next() shouldBe Some("two")
    h.next() shouldBe Some("three")
    h.next() shouldBe Some("drafting")
    h.recalling shouldBe false
    h.add("four")
    h.entries shouldBe Seq("two", "three", "four")
  }

  test("selection selects, toggles, and extends a range from the anchor") {
    val selected = Rx.variable(Set.empty[String])
    val s        = Selection(selected)
    val order    = Seq("a", "b", "c", "d", "e")
    s.select("b")
    s.extendTo("d", order)
    selected.get shouldBe Set("b", "c", "d")
    s.extendTo("a", order)
    selected.get shouldBe Set("a", "b")
    s.anchor shouldBe Some("b")
    s.toggle("e")
    selected.get shouldBe Set("a", "b", "e")
    s.toggle("a")
    selected.get shouldBe Set("b", "e")
    s.selectAll(order)
    selected.get shouldBe order.toSet
    s.clear()
    selected.get shouldBe Set.empty
  }

  test("selection reports each change, and a canceled one keeps the selection") {
    val selected = Rx.variable(Set("a"))
    val seen     = ArrayBuffer.empty[ChangeDetails[Any]]
    val s        = Selection(selected).onChange { d =>
      seen += d
      if d.value == Set.empty then
        d.cancel()
    }
    s.toggle("b", ChangeCause(ChangeReason.ItemPress, ChangeSource.Pointer)) shouldBe true
    seen.last.key shouldBe "selected"
    seen.last.previous shouldBe Set("a")
    seen.last.value shouldBe Set("a", "b")
    seen.last.source shouldBe ChangeSource.Pointer
    s.clear() shouldBe false
    selected.get shouldBe Set("a", "b")
  }

  test("a range ignores keys outside the order") {
    Selection.range(Seq(1, 2, 3), 3, 1) shouldBe Set(1, 2, 3)
    Selection.range(Seq(1, 2, 3), 9, 2) shouldBe Set(2)
  }

  test("focus recovery picks the next item, else the previous") {
    FocusRecovery.neighbor(Seq("a", "b", "c"), "b") shouldBe Some("c")
    FocusRecovery.neighbor(Seq("a", "b", "c"), "c") shouldBe Some("b")
    FocusRecovery.neighbor(Seq("a"), "a") shouldBe None
    FocusRecovery.neighbor(Seq("a"), "x") shouldBe None
  }

  test("near the bottom means within the threshold") {
    StickToBottom.isNear(900, 1000, 100, 0) shouldBe true
    StickToBottom.isNear(860, 1000, 100, 48) shouldBe true
    StickToBottom.isNear(800, 1000, 100, 48) shouldBe false
  }

  test("reorder moves an item to a position, clamped to the list") {
    val abc = Seq("a", "b", "c")
    Reorder.moved(abc, 0, 2) shouldBe Seq("b", "c", "a")
    Reorder.moved(abc, 2, 0) shouldBe Seq("c", "a", "b")
    Reorder.moved(abc, 1, 9) shouldBe Seq("a", "c", "b")
    Reorder.moved(abc, 5, 0) shouldBe abc
  }

  test("reorder turns an insertion point into the position the item lands on") {
    // Inserting after the item itself: removing it first shifts later items left
    Reorder.landing(1, 3) shouldBe 2
    Reorder.landing(1, 2) shouldBe 1
    Reorder.landing(1, 0) shouldBe 0
    Reorder.dropIndex(Seq(10.0, 30.0, 50.0), 5) shouldBe 0
    Reorder.dropIndex(Seq(10.0, 30.0, 50.0), 31) shouldBe 2
    Reorder.dropIndex(Seq(10.0, 30.0, 50.0), 99) shouldBe 3
  }

  test("reorder keys move one step along the axis, or to either end") {
    Reorder.keyTarget("ArrowDown", 0, 3) shouldBe Some(1)
    Reorder.keyTarget("ArrowUp", 0, 3) shouldBe None
    Reorder.keyTarget("End", 0, 3) shouldBe Some(2)
    Reorder.keyTarget("Home", 2, 3) shouldBe Some(0)
    Reorder.keyTarget("Home", 0, 3) shouldBe None
    Reorder.keyTarget("ArrowLeft", 1, 3, Orientation.Horizontal) shouldBe Some(0)
    Reorder.keyTarget("ArrowLeft", 1, 3) shouldBe None
    Reorder.keyTarget(" ", 1, 3) shouldBe None
  }

end BehaviorModelTest
