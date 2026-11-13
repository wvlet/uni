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

class RovingFocusTest extends UniTest:

  test("horizontal arrows wrap, Home and End jump") {
    RovingFocus.nextIndex("ArrowRight", 0, 3) shouldBe Some(1)
    RovingFocus.nextIndex("ArrowRight", 2, 3) shouldBe Some(0)
    RovingFocus.nextIndex("ArrowLeft", 0, 3) shouldBe Some(2)
    RovingFocus.nextIndex("Home", 2, 3) shouldBe Some(0)
    RovingFocus.nextIndex("End", 0, 3) shouldBe Some(2)
  }

  test("keys off the axis and other keys do not navigate") {
    RovingFocus.nextIndex("ArrowDown", 0, 3) shouldBe None
    RovingFocus.nextIndex("a", 0, 3) shouldBe None
    RovingFocus.nextIndex("ArrowDown", 0, 3, Orientation.Vertical) shouldBe Some(1)
    RovingFocus.nextIndex("ArrowLeft", 0, 3, Orientation.Vertical) shouldBe None
  }

  test("horizontal arrows mirror in a right-to-left group") {
    RovingFocus.nextIndex("ArrowLeft", 0, 3, rtl = true) shouldBe Some(1)
    RovingFocus.nextIndex("ArrowRight", 0, 3, rtl = true) shouldBe Some(2)
    RovingFocus.nextIndex("End", 0, 3, rtl = true) shouldBe Some(2)
    // Vertical groups don't mirror
    RovingFocus.nextIndex("ArrowDown", 0, 3, Orientation.Vertical, rtl = true) shouldBe Some(1)
    RovingFocus.nextIndex("ArrowLeft", 0, 3, Orientation.Vertical, rtl = true) shouldBe None
  }

  test("logicalKey swaps only ArrowLeft and ArrowRight, only right to left") {
    TextDirection.logicalKey("ArrowLeft", rtl = true) shouldBe "ArrowRight"
    TextDirection.logicalKey("ArrowRight", rtl = true) shouldBe "ArrowLeft"
    TextDirection.logicalKey("ArrowUp", rtl = true) shouldBe "ArrowUp"
    TextDirection.logicalKey("ArrowLeft", rtl = false) shouldBe "ArrowLeft"
  }

  test("an empty group never navigates") {
    RovingFocus.nextIndex("Home", 0, 0) shouldBe None
  }

  test("typeahead finds the next label with the typed letter, wrapping") {
    val labels = Seq("Copy", "Cut", "Paste")
    RovingFocus.typeahead(labels, 0, "c") shouldBe Some(1)
    RovingFocus.typeahead(labels, 1, "C") shouldBe Some(0)
    RovingFocus.typeahead(labels, -1, "p") shouldBe Some(2)
    RovingFocus.typeahead(labels, 0, "x") shouldBe None
  }

  test("with no active item, the next key goes to the first and the previous to the last") {
    RovingFocus.nextIndex("ArrowDown", -1, 3, Orientation.Vertical) shouldBe Some(0)
    RovingFocus.nextIndex("ArrowUp", -1, 3, Orientation.Vertical) shouldBe Some(2)
  }

end RovingFocusTest
