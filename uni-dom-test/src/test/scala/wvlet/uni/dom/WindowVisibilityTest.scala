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

class WindowVisibilityTest extends UniTest:

  test("WindowVisibility.isVisible returns current status") {
    val status = WindowVisibility.isVisible
    status shouldMatch { case _: Boolean =>
    }
  }

  test("WindowVisibility.state returns visibility state string") {
    val state = WindowVisibility.state
    state shouldMatch { case _: String =>
    }
  }

  test("WindowVisibility.visible returns Rx[Boolean]") {
    val rx = WindowVisibility.visible
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowVisibility.hidden returns Rx[Boolean]") {
    val rx = WindowVisibility.hidden
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowVisibility.visibilityState returns Rx[String]") {
    val rx = WindowVisibility.visibilityState
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowVisibility.visible and hidden are inverses") {
    var visibleValue = false
    var hiddenValue  = false

    WindowVisibility
      .visible
      .run { v =>
        visibleValue = v
      }
    WindowVisibility
      .hidden
      .run { v =>
        hiddenValue = v
      }

    visibleValue shouldBe !hiddenValue
  }

  test("WindowVisibility can be used in reactive expressions") {
    val statusText = WindowVisibility
      .visible
      .map { visible =>
        if visible then
          "Visible"
        else
          "Hidden"
      }

    var result = ""
    statusText.run { v =>
      result = v
    }

    (result == "Visible" || result == "Hidden") shouldBe true
  }

end WindowVisibilityTest
