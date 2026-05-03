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
package example.dom

import wvlet.uni.test.UniTest
import wvlet.uni.dom.{DomAttribute, DomElement}
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

/**
  * Pins the airframe-rx-html migration contract for the `style` / `title` attribute names. In uni's
  * `all`, `style` and `title` resolve to the corresponding HTML *elements* (the `<style>` and
  * `<title>` tags), so the migration uses `styleAttr` / `titleAttr` for the attribute form. See
  * issue #524.
  *
  * Lives in `package example.dom` so the import goes through `import wvlet.uni.dom.all.*` only — a
  * sibling under `package wvlet.uni.dom` would see `styleAttr` via package-level visibility even if
  * the `all` mixin dropped the attribute.
  */
class StyleAttrTest extends UniTest:

  test("styleAttr produces an inline CSS attribute through `import all.*`"):
    val d = div(styleAttr -> "height: 64px; color: red;")
    d.modifiers.flatten.head shouldMatch { case a: DomAttribute =>
      a.name shouldBe "style"
      a.v shouldBe "height: 64px; color: red;"
    }

  test("titleAttr produces a tooltip attribute through `import all.*`"):
    val b = button(titleAttr -> "Save changes")
    b.modifiers.flatten.head shouldMatch { case a: DomAttribute =>
      a.name shouldBe "title"
      a.v shouldBe "Save changes"
    }

  test("`style` in scope is the <style> tag, not an attribute"):
    // Plain `style` is the `<style>` element in `wvlet.uni.dom.all` — verify it resolves to
    // a `DomElement` (and so accepts CSS rule text as a child), not a `DomAttributeOf`.
    val s = style("body { margin: 0; }")
    s shouldMatch { case e: DomElement =>
      e.name shouldBe "style"
    }

  test("`title` in scope is the <title> tag, not an attribute"):
    val t = title("Page Title")
    t shouldMatch { case e: DomElement =>
      e.name shouldBe "title"
    }

end StyleAttrTest
