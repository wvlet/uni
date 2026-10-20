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

class WindowDimensionsTest extends UniTest:

  test("WindowDimensions.innerWidth returns current viewport width"):
    val width = WindowDimensions.innerWidth
    width shouldMatch { case _: Int =>
    }

  test("WindowDimensions.innerHeight returns current viewport height"):
    val height = WindowDimensions.innerHeight
    height shouldMatch { case _: Int =>
    }

  test("WindowDimensions.outerWidth returns current window width"):
    val width = WindowDimensions.outerWidth
    width shouldMatch { case _: Int =>
    }

  test("WindowDimensions.outerHeight returns current window height"):
    val height = WindowDimensions.outerHeight
    height shouldMatch { case _: Int =>
    }

  test("WindowDimensions.rxInnerWidth returns Rx[Int]"):
    val rx = WindowDimensions.rxInnerWidth
    rx shouldMatch { case _: Rx[?] =>
    }

  test("WindowDimensions.rxInnerHeight returns Rx[Int]"):
    val rx = WindowDimensions.rxInnerHeight
    rx shouldMatch { case _: Rx[?] =>
    }

  test("WindowDimensions.rxOuterWidth returns Rx[Int]"):
    val rx = WindowDimensions.rxOuterWidth
    rx shouldMatch { case _: Rx[?] =>
    }

  test("WindowDimensions.rxOuterHeight returns Rx[Int]"):
    val rx = WindowDimensions.rxOuterHeight
    rx shouldMatch { case _: Rx[?] =>
    }

  test("WindowDimensions.dimensions returns Rx[(Int, Int)]"):
    val rx = WindowDimensions.dimensions
    rx shouldMatch { case _: Rx[?] =>
    }

  test("WindowDimensions can be used in reactive expressions"):
    val sizeText = WindowDimensions
      .rxInnerWidth
      .map { width =>
        if width < 768 then
          "mobile"
        else
          "desktop"
      }

    var result = ""
    sizeText.run { v =>
      result = v
    }

    (result == "mobile" || result == "desktop") shouldBe true

  test("WindowDimensions.dimensions provides both width and height"):
    var widthResult  = 0
    var heightResult = 0

    WindowDimensions
      .dimensions
      .run { case (w, h) =>
        widthResult = w
        heightResult = h
      }

    (widthResult >= 0) shouldBe true
    (heightResult >= 0) shouldBe true

  test("WindowDimensions.dimensions follows a real viewport resize"):
    val initial = (WindowDimensions.innerWidth, WindowDimensions.innerHeight)
    Playwright
      .withViewportSize(500, 400)(awaitValue(WindowDimensions.dimensions)(_ == (500, 400)))
      .flatMap(_ => awaitValue(WindowDimensions.dimensions)(_ == initial))
      .map(_ => ())

end WindowDimensionsTest
