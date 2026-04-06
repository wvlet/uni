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
package wvlet.uni.text

import wvlet.uni.test.UniTest

class CodeFormatterTest extends UniTest:

  import CodeFormatter.{text, nest, group, empty, cl, lines, verticalAppend, newline, wsOrNL}

  test("render simple text") {
    text("hello").render() shouldBe "hello"
  }

  test("concat text horizontally") {
    val doc = text("hello") + text(" ") + text("world")
    doc.render() shouldBe "hello world"
  }

  test("concat text vertically") {
    val doc = text("line1") / text("line2")
    doc.render() shouldBe "line1\nline2"
  }

  test("nest adds indentation after newline") {
    // nest() increases indent level; newline renders with indent at that level
    val doc = text("outer:") + nest(newline + text("inner"))
    doc.render() shouldBe "outer:\n  inner"
  }

  test("nested indentation accumulates") {
    val doc = text("level0:") + nest(newline + text("level1:") + nest(newline + text("level2")))
    doc.render() shouldBe "level0:\n  level1:\n    level2"
  }

  test("group flattens to single line when it fits") {
    val doc = group(text("a") / text("b") / text("c"))
    doc.render() shouldBe "a b c"
  }

  test("group breaks to multi-line when too wide") {
    val config = CodeFormatterConfig(maxLineWidth = 5)
    val doc    = group(text("hello") / text("world"))
    doc.render(config) shouldBe "hello\nworld"
  }

  test("comma-separated list fits on one line") {
    // cl uses wsOrNL separator, which is compacted by group
    val doc = group(cl(text("a"), text("b"), text("c")))
    doc.render() shouldBe "a, b, c"
  }

  test("lines concatenates with newlines") {
    val doc = lines(List(text("a"), text("b"), text("c")))
    doc.render() shouldBe "a\nb\nc"
  }

  test("empty doc is identity for concat") {
    val doc = empty + text("hello") + empty
    doc.render() shouldBe "hello"
  }

  test("verticalAppend with separator") {
    val doc    = verticalAppend(List(text("a"), text("b"), text("c")), text("---"))
    val result = doc.render()
    result.contains("a") shouldBe true
    result.contains("b") shouldBe true
    result.contains("---") shouldBe true
  }

end CodeFormatterTest
