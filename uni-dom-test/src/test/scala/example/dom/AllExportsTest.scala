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
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

/**
  * Pins the airframe-rx-html → uni migration contract: top-level helpers like `when` and `unless`
  * (defined as top-level defs in package `wvlet.uni.dom`) must remain reachable purely through
  * `import wvlet.uni.dom.all.*`.
  *
  * Lives in package `example.dom` deliberately — siblings under `package wvlet.uni.dom.*` would see
  * those defs via package visibility even if `all` stopped re-exporting them, so a regression of
  * the export there would be invisible to the test suite. See issue #526.
  */
class AllExportsTest extends UniTest:

  test("when reachable through import wvlet.uni.dom.all.*"):
    val visible = true
    val d       = div(when(visible, span("Visible")))
    // Verify the actual rendered element, not just modifier count — `DomNode.empty`
    // also occupies a slot, so size=1 alone wouldn't distinguish success from a
    // regression where `when(true, _)` returned empty.
    d.modifiers.flatten.head shouldMatch { case e: DomElement =>
      e.name shouldBe "span"
    }

  test("unless reachable through import wvlet.uni.dom.all.*"):
    val hidden = false
    val d      = div(unless(hidden, span("Visible")))
    d.modifiers.flatten.head shouldMatch { case e: DomElement =>
      e.name shouldBe "span"
    }

end AllExportsTest
