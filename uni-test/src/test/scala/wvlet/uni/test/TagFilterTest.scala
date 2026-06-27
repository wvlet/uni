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

import wvlet.uni.test.spi.TestConfig

/**
  * Tag-based layer selection: `--tags`/`--exclude-tags` (each repeatable) let one suite span
  * multiple testing layers (unit, UI, electron) and still be run layer-by-layer, mirroring VSCode's
  * per-layer test commands.
  */
class TagFilterTest extends UniTest:

  test("parses --tags as a repeatable include filter") {
    val c = TestConfig.parse(Array("--tags", "ui", "--tags", "electron"))
    c.includeTags shouldBe Set("ui", "electron")
    c.excludeTags shouldBe Set.empty
  }

  test("parses --exclude-tags as a repeatable exclude filter") {
    val c = TestConfig.parse(Array("--exclude-tags", "slow", "--exclude-tags", "flaky"))
    c.excludeTags shouldBe Set("slow", "flaky")
    c.includeTags shouldBe Set.empty
  }

  test("trims whitespace and ignores an empty tag value") {
    val c = TestConfig.parse(Array("--tags", " ui ", "--tags", "  "))
    c.includeTags shouldBe Set("ui")
  }

  test("a trailing --tags with no value is ignored") {
    val c = TestConfig.parse(Array("--tags"))
    c.includeTags shouldBe Set.empty
  }

  test("no tag filter runs every test") {
    val c = TestConfig.parse(Array.empty[String])
    c.includesTags(Set.empty) shouldBe true
    c.includesTags(Set("ui")) shouldBe true
  }

  test("include filter keeps only tests carrying a listed tag") {
    val c = TestConfig.parse(Array("--tags", "ui"))
    c.includesTags(Set("ui")) shouldBe true
    c.includesTags(Set("ui", "slow")) shouldBe true
    c.includesTags(Set("electron")) shouldBe false
    c.includesTags(Set.empty) shouldBe false
  }

  test("exclude filter drops tests carrying a listed tag") {
    val c = TestConfig.parse(Array("--exclude-tags", "slow"))
    c.includesTags(Set("slow")) shouldBe false
    c.includesTags(Set("ui")) shouldBe true
    c.includesTags(Set.empty) shouldBe true
  }

  test("exclusion wins over inclusion") {
    val c = TestConfig.parse(Array("--tags", "ui", "--exclude-tags", "slow"))
    c.includesTags(Set("ui")) shouldBe true
    c.includesTags(Set("ui", "slow")) shouldBe false
  }

  // The TestTag varargs on test() flow through to the registered TestDef.
  test("tagged test registers its tags", TestTag("meta"), TestTag("self")) {
    val tagged = registeredTests.find(_.name == "tagged test registers its tags").get
    tagged.tags shouldBe Set("meta", "self")
  }

  test("built-in layer tags carry their lowercase names", UI, Electron) {
    val t = registeredTests.find(_.name == "built-in layer tags carry their lowercase names").get
    t.tags shouldBe Set("ui", "electron")
    t.isFlaky shouldBe false
  }

  test("the Flaky tag sets isFlaky and is filterable as 'flaky'", Flaky, Slow) {
    val t = registeredTests.find(_.name.startsWith("the Flaky tag")).get
    t.isFlaky shouldBe true
    t.tags shouldBe Set("flaky", "slow")
  }

  test("TestTag values expose stable names") {
    TestTag.Flaky.name shouldBe "flaky"
    TestTag.UI.name shouldBe "ui"
    TestTag.Smoke.name shouldBe "smoke"
    TestTag("custom").name shouldBe "custom"
  }

  test("a String literal is accepted as a custom tag") {
    import scala.language.implicitConversions
    val tag: TestTag = "smoke"
    tag.name shouldBe "smoke"
  }

end TagFilterTest
