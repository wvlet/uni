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

import org.scalajs.dom
import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx

import scala.scalajs.js

class DomObserversTest extends UniTest:

  test("IntersectionConfig has sensible defaults") {
    val config = IntersectionConfig.default
    config.root shouldBe None
    config.rootMargin shouldBe "0px"
    config.threshold shouldBe Seq(0.0)
  }

  test("IntersectionConfig can be customized") {
    val config = IntersectionConfig(
      rootMargin = "10px 20px",
      threshold = Seq(0.0, 0.25, 0.5, 0.75, 1.0)
    )
    config.rootMargin shouldBe "10px 20px"
    config.threshold shouldBe Seq(0.0, 0.25, 0.5, 0.75, 1.0)
  }

  test("IntersectionEntry case class holds data") {
    val mockRect   = dom.document.createElement("div").getBoundingClientRect()
    val mockTarget = dom.document.createElement("div")
    val entry      = IntersectionEntry(
      isIntersecting = true,
      intersectionRatio = 0.5,
      boundingClientRect = mockRect,
      rootBounds = Some(mockRect),
      target = mockTarget
    )
    entry.isIntersecting shouldBe true
    entry.intersectionRatio shouldBe 0.5
  }

  test("Intersection.observe creates binding") {
    val isVisible = Rx.variable(false)
    val binding   = Intersection.observe(isVisible)
    binding shouldMatch { case _: IntersectionBinding =>
    }
  }

  test("Intersection.observeEntry creates entry binding") {
    val entry   = Rx.variable(Option.empty[IntersectionEntry])
    val binding = Intersection.observeEntry(entry)
    binding shouldMatch { case _: IntersectionEntryBinding =>
    }
  }

  test("Intersection.onceVisible creates once binding") {
    var called  = false
    val binding = Intersection.onceVisible(() => called = true)
    binding shouldMatch { case _: IntersectionOnceBinding =>
    }
  }

  test("ResizeEntry case class holds data") {
    val mockRect   = dom.document.createElement("div").getBoundingClientRect()
    val mockTarget = dom.document.createElement("div")
    val entry      = ResizeEntry(contentRect = mockRect, target = mockTarget)
    entry.target shouldBe mockTarget
  }

  test("Resize.observe creates binding") {
    val size    = Rx.variable((0.0, 0.0))
    val binding = Resize.observe(size)
    binding shouldMatch { case _: ResizeBinding =>
    }
  }

  test("Resize.observeEntry creates entry binding") {
    val entry   = Rx.variable(Option.empty[ResizeEntry])
    val binding = Resize.observeEntry(entry)
    binding shouldMatch { case _: ResizeEntryBinding =>
    }
  }

  test("Resize.observeDebounced creates debounced binding") {
    val size    = Rx.variable((0.0, 0.0))
    val binding = Resize.observeDebounced(size, 150)
    binding shouldMatch { case rbd: ResizeBindingDebounced =>
      rbd.debounceMs shouldBe 150
    }
  }

  // Note: Full integration tests for IntersectionObserver and ResizeObserver
  // require browser environment. jsdom doesn't support these APIs.
  // The bindings are created correctly (tested above) and the handler code
  // in DomRenderer will work in a real browser environment.

end DomObserversTest
