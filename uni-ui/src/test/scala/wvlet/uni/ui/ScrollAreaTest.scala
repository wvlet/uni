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
package wvlet.uni.ui

import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.dom.testing.*

import scala.language.implicitConversions

class ScrollAreaTest extends DomTestSupport:

  private val stylesheet = dom.document.createElement("style")
  // An app's unlayered scrollbar styling, as in the gallery, must not bring the scrollbar back
  stylesheet.textContent = UniUiCss.text + "\n* { scrollbar-width: thin; }"

  override protected def beforeAll: Unit = dom.document.head.appendChild(stylesheet)
  override protected def afterAll: Unit  = dom.document.head.removeChild(stylesheet)

  private def area(contentWidth: Int) = mount(
    div(
      styleAttr -> "width: 200px",
      ScrollArea(div(styleAttr -> s"width: ${contentWidth}px; height: 40px", "content"))
    )
  )

  test("the thumb is sized and placed by the part of the content in view") {
    ScrollArea.thumb(200, 200, 0) shouldBe None
    ScrollArea.thumb(200, 800, 0) shouldBe Some((50.0, 0.0))
    ScrollArea.thumb(200, 800, 600) shouldBe Some((50.0, 150.0))
    // Very wide content keeps a thumb to grab
    ScrollArea.thumb(200, 100000, 0).map(_._1) shouldBe Some(ScrollArea.minThumbWidth)
  }

  test("a scroll area takes no room below its content and moves its thumb as it scrolls") {
    val m     = area(800)
    val root  = m.get(".uni-scroll-area").asInstanceOf[dom.HTMLElement]
    val view  = m.get(".uni-scroll-area__view").asInstanceOf[dom.HTMLElement]
    val thumb = m.get(".uni-scroll-area__thumb").asInstanceOf[dom.HTMLElement]
    settle().map { _ =>
      // No scrollbar strip: the area is exactly as tall as its content
      root.offsetHeight shouldBe 40
      dom.window.getComputedStyle(view).getPropertyValue("scrollbar-width") shouldBe "none"
      view.clientWidth shouldBe 200
      thumb.style.display shouldBe "block"
      thumb.style.width shouldBe "50px"
      m.get(".uni-scroll-area__bar").getAttribute("aria-hidden") shouldBe "true"
      // Hidden until the pointer is on the area
      dom.window.getComputedStyle(thumb).opacity shouldBe "0"
      view.scrollLeft = 600
      fireEvent.dispatch(view, dom.Event("scroll"))
      thumb.style.transform shouldBe "translateX(150px)"
    }
  }

  test("a scroll area whose content fits shows no thumb and is as wide as the content") {
    val m = area(120)
    settle().map { _ =>
      m.get(".uni-scroll-area__thumb").asInstanceOf[dom.HTMLElement].style.display shouldBe "none"
      m.get(".uni-scroll-area").asInstanceOf[dom.HTMLElement].offsetWidth shouldBe 120
    }
  }

end ScrollAreaTest
