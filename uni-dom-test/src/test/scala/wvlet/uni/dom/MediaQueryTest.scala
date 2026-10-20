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
package wvlet.uni.dom

import wvlet.uni.test.UniTest
import wvlet.uni.test.playwright.Playwright
import wvlet.uni.dom.all.*
import wvlet.uni.dom.testing.awaitValue
import wvlet.uni.rx.Rx

class MediaQueryTest extends UniTest:

  // These run in a real headless Chromium (Playwright), so window.matchMedia is
  // available and the matchers can be evaluated at runtime, not just compiled.

  test("MediaQuery object exists"):
    MediaQuery shouldNotBe null

  test("a tautological query matches"):
    // (min-width: 0px) is true for any viewport, so this is deterministic.
    MediaQuery.matches("(min-width: 0px)").get shouldBe true

  test("the `all` media type always matches"):
    MediaQuery.matches("all").get shouldBe true

  test("an impossibly wide query does not match"):
    // No real viewport is this wide, so matchMedia must evaluate this to false —
    // proving the query is checked against the viewport, not blindly accepted.
    MediaQuery.matches("(min-width: 999999px)").get shouldBe false

  test("rx is seeded with the current match state"):
    val matcher = MediaQuery.matches("(min-width: 0px)")
    var seeded  = false
    val c       = matcher.rx.run(seeded = _)
    seeded shouldBe matcher.get
    c.cancel
    matcher.cancel

  test("matcher can be cancelled without error"):
    val matcher = MediaQuery.matches("(min-width: 0px)")
    matcher.cancel

  test("a matcher follows a real viewport resize"):
    val narrow = MediaQuery.matches("(max-width: 600px)")
    narrow.get shouldBe false
    Playwright
      .withViewportSize(500, 400)(awaitValue(narrow.rx)(identity))
      .flatMap(_ => awaitValue(narrow.rx)(!_))
      .transformRx { result =>
        narrow.cancel
        Rx.fromTry(result).map(_ => ())
      }

end MediaQueryTest
