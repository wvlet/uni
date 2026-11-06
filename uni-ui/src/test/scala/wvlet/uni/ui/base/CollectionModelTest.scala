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

class CollectionModelTest extends UniTest:

  test("windowing finds the rows in view and widens them by the overscan"):
    Windowing.inView(scrollTop = 0, viewportHeight = 200, rowHeight = 20) shouldBe (0, 11)
    Windowing.inView(scrollTop = 410, viewportHeight = 200, rowHeight = 20) shouldBe (20, 11)
    Windowing.range(first = 20, visible = 11, count = 1000, overscan = 5) shouldBe (15, 36)
    Windowing.range(first = 0, visible = 11, count = 5, overscan = 5) shouldBe (0, 5)
    Windowing.range(first = 0, visible = 11, count = 0, overscan = 5) shouldBe (0, 0)

  test("reveal scrolls the least needed to show a row"):
    Windowing.reveal(index = 3, rowHeight = 20, scrollTop = 0, viewportHeight = 200) shouldBe None
    Windowing.reveal(index = 2, rowHeight = 20, scrollTop = 100, viewportHeight = 200) shouldBe
      Some(40.0)
    Windowing.reveal(index = 20, rowHeight = 20, scrollTop = 0, viewportHeight = 200) shouldBe
      Some(220.0)

  test("list navigation stops at the ends and moves by pages"):
    ListNavigation.nextIndex("ArrowDown", -1, 10, 4) shouldBe Some(0)
    ListNavigation.nextIndex("ArrowUp", -1, 10, 4) shouldBe Some(9)
    ListNavigation.nextIndex("ArrowDown", 9, 10, 4) shouldBe Some(9)
    ListNavigation.nextIndex("ArrowUp", 0, 10, 4) shouldBe Some(0)
    ListNavigation.nextIndex("PageDown", 2, 10, 4) shouldBe Some(6)
    ListNavigation.nextIndex("PageDown", 8, 10, 4) shouldBe Some(9)
    ListNavigation.nextIndex("PageUp", 2, 10, 4) shouldBe Some(0)
    ListNavigation.nextIndex("End", 0, 10, 4) shouldBe Some(9)
    ListNavigation.nextIndex("a", 0, 10, 4) shouldBe None
    ListNavigation.nextIndex("ArrowDown", -1, 0, 4) shouldBe None

  test("fuzzy matching finds subsequences and marks the matched letters"):
    Fuzzy.matches("", "Anything").map(_.positions) shouldBe Some(Seq.empty)
    Fuzzy.matches("chat", "New chat").map(_.positions) shouldBe Some(Seq(4, 5, 6, 7))
    Fuzzy.matches("tgd", "Toggle dock").map(_.positions) shouldBe Some(Seq(0, 2, 7))
    Fuzzy.matches("nc", "New chat").map(_.positions) shouldBe Some(Seq(0, 4))
    Fuzzy.matches("N C", "new chat").isDefined shouldBe true
    Fuzzy.matches("xyz", "Toggle dock") shouldBe None

  test("fuzzy matching prefers word starts, then falls back to any subsequence"):
    // "sb" takes the word start of "bar", not the "b" inside "sub"
    Fuzzy.matches("sb", "sub bar").map(_.positions) shouldBe Some(Seq(0, 4))
    // Jumping to the word start "Bx" would leave no "c" after it; the plain match still succeeds
    Fuzzy.matches("bxc", "ab xc Bx").map(_.positions) shouldBe Some(Seq(1, 3, 4))

  test("fuzzy positions stay on the original text when lowercasing would lengthen it"):
    // "İ".toLowerCase is two chars; matching must not shift positions past it
    Fuzzy.matches("trip", "İstanbul trip").map(_.positions) shouldBe Some(Seq(9, 10, 11, 12))
    Fuzzy.matches("p", "İstanbul trip").map(_.positions) shouldBe Some(Seq(12))
    Fuzzy.matches("itp", "İstanbul trip").map(_.positions) shouldBe Some(Seq(0, 9, 12))

  test("fuzzy scores rank contiguous and word-start matches first"):
    def score(q: String, t: String) = Fuzzy.matches(q, t).map(_.score).getOrElse(-1.0)
    (score("chat", "New chat") > score("chat", "Change history at")) shouldBe true
    (score("nc", "New chat") > score("nc", "Open console")) shouldBe true
    (score("dock", "Toggle dock") > score("dock", "Toggle sidebar dock settings")) shouldBe true

  private case class Node(name: String, kids: Seq[Node] = Seq.empty)

  private val roots = Seq(
    Node("src", Seq(Node("main", Seq(Node("App.scala"))), Node("test"))),
    Node("README.md")
  )

  private def flat(expanded: Set[String]) = TreeModel.flatten(roots, _.kids, _.name, expanded)

  test("a tree flattens to its visible rows with levels and positions"):
    flat(Set.empty).map(r => (r.key, r.level, r.position, r.siblings)) shouldBe
      Seq(("src", 1, 1, 2), ("README.md", 1, 2, 2))
    val open = flat(Set("src", "main"))
    open.map(_.key) shouldBe Seq("src", "main", "App.scala", "test", "README.md")
    open.map(_.level) shouldBe Seq(1, 2, 3, 2, 1)
    open.map(_.parent) shouldBe Seq(None, Some("src"), Some("main"), Some("src"), None)
    open.map(_.expandable) shouldBe Seq(true, true, false, false, false)
    // An expanded key for a leaf or a hidden row changes nothing
    flat(Set("main", "README.md")).map(_.key) shouldBe Seq("src", "README.md")

  test("Right and Left open, enter, close, and leave tree rows"):
    val closed = flat(Set.empty)
    TreeModel.onKey("ArrowRight", closed, 0, 1) shouldBe Some(TreeAction.Expand("src"))
    TreeModel.onKey("ArrowRight", closed, 1, 1) shouldBe None
    val open = flat(Set("src"))
    TreeModel.onKey("ArrowRight", open, 0, 1) shouldBe Some(TreeAction.Move(1))
    TreeModel.onKey("ArrowLeft", open, 0, 1) shouldBe Some(TreeAction.Collapse("src"))
    TreeModel.onKey("ArrowLeft", open, 2, 1) shouldBe Some(TreeAction.Move(0))
    TreeModel.onKey("ArrowLeft", open, 3, 1) shouldBe None
    TreeModel.onKey("ArrowDown", open, 3, 1) shouldBe Some(TreeAction.Move(3))

end CollectionModelTest
