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
import wvlet.uni.dom.{DomAttributeOf, HtmlTags}

// Targeted-rename import: drop SVG `xmlns` (which collides with the HTML xmlns
// attribute that a real migration usually has in scope already), keep the rest.
// This import only compiles if `wvlet.uni.dom.SvgAttrs` resolves as an *object* — a
// pure trait cannot be imported with the renames-and-wildcard form. See issue #528.
import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}

/**
  * Pins the airframe-rx-html migration contract: `wvlet.uni.dom.SvgAttrs` is reachable as an
  * *object* so `import wvlet.uni.dom.SvgAttrs.{xmlns as _, *}` works, mirroring the airframe
  * `import wvlet.airframe.rx.html.svgAttrs.{xmlns as _, ...}` style.
  */
class SvgAttrsImportTest extends UniTest:

  test("targeted SVG attribute import compiles and exposes SVG attrs"):
    // `cx`, `r`, `fill` are SVG attributes brought in by the wildcard.
    val center: DomAttributeOf = cx
    val radius: DomAttributeOf = r
    val color: DomAttributeOf  = fill
    center shouldBe HtmlTags.attr("cx")
    radius shouldBe HtmlTags.attr("r")
    color shouldBe HtmlTags.attr("fill")

  test("renamed-out SVG `xmlns` is not in scope"):
    // `xmlns` was excluded with `as _`. It must not bind here.
    val unqualifiedXmlns = scala.compiletime.testing.typeChecks("val a = xmlns")
    unqualifiedXmlns shouldBe false

end SvgAttrsImportTest
