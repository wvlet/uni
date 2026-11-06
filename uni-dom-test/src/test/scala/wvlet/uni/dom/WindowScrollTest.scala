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

class WindowScrollTest extends UniTest:

  test("WindowScroll.x returns current horizontal scroll position") {
    val x = WindowScroll.x
    x shouldMatch { case _: Double =>
    }
  }

  test("WindowScroll.y returns current vertical scroll position") {
    val y = WindowScroll.y
    y shouldMatch { case _: Double =>
    }
  }

  test("WindowScroll.scrollX returns Rx[Double]") {
    val rx = WindowScroll.scrollX
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowScroll.scrollY returns Rx[Double]") {
    val rx = WindowScroll.scrollY
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowScroll.scroll returns Rx[(Double, Double)]") {
    val rx = WindowScroll.scroll
    rx shouldMatch { case _: Rx[?] =>
    }
  }

  test("WindowScroll can be used in reactive expressions") {
    val showBackToTop = WindowScroll
      .scrollY
      .map { y =>
        y > 500
      }

    var result = false
    showBackToTop.run { v =>
      result = v
    }

    result shouldMatch { case _: Boolean =>
    }
  }

  test("WindowScroll.scroll provides both x and y positions") {
    var xResult = 0.0
    var yResult = 0.0

    WindowScroll
      .scroll
      .run { case (x, y) =>
        xResult = x
        yResult = y
      }

    // In jsdom, scroll positions start at 0
    (xResult >= 0) shouldBe true
    (yResult >= 0) shouldBe true
  }

end WindowScrollTest
