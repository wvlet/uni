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
import wvlet.uni.dom.all.*
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

  test("matcher exposes a reactive Boolean stream"):
    val matcher = MediaQuery.matches("(min-width: 0px)")
    matcher.rx shouldMatch { case _: Rx[?] =>
    }
    matcher.get shouldBe true

  test("device-class queries evaluate to a Boolean"):
    // Exact values depend on the headless viewport, but each must resolve to a Boolean.
    MediaQuery.matches("(max-width: 767px)").get shouldMatch { case _: Boolean =>
    }
    MediaQuery.matches("(min-width: 1024px)").get shouldMatch { case _: Boolean =>
    }

  test("preference queries evaluate to a Boolean"):
    MediaQuery.matches("(prefers-color-scheme: dark)").get shouldMatch { case _: Boolean =>
    }
    MediaQuery.matches("(prefers-reduced-motion: reduce)").get shouldMatch { case _: Boolean =>
    }

  test("matcher can be cancelled without error"):
    val matcher = MediaQuery.matches("(min-width: 0px)")
    matcher.cancel

end MediaQueryTest
