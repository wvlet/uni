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
import scala.language.implicitConversions
import wvlet.uni.rx.Rx

import scala.concurrent.Future

class ClipboardTest extends UniTest:

  test("Clipboard.isSupported returns Boolean"):
    val supported = Clipboard.isSupported
    supported shouldMatch { case _: Boolean =>
    }

  test("Clipboard.writeText returns Future[Unit]"):
    val result = Clipboard.writeText("test")
    result shouldMatch { case _: Future[?] =>
    }

  test("Clipboard.readText returns Future[String]"):
    val result = Clipboard.readText()
    result shouldMatch { case _: Future[?] =>
    }

  test("Clipboard.writeTextRx returns Rx[Option[Boolean]]"):
    val result = Clipboard.writeTextRx("test")
    result shouldMatch { case _: Rx[?] =>
    }

  test("Clipboard.onCopy returns DomNode"):
    val node = Clipboard.onCopy(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.onCut returns DomNode"):
    val node = Clipboard.onCut(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.onPaste returns DomNode"):
    val node = Clipboard.onPaste(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.onPasteEvent returns DomNode"):
    val node = Clipboard.onPasteEvent(_ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.copyAs returns DomNode"):
    val node = Clipboard.copyAs(() => "text")
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.cutAs returns DomNode"):
    val node = Clipboard.cutAs(() => "text")
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.copyOnClick returns DomNode"):
    val node = Clipboard.copyOnClick("text")
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.copyOnClick with callbacks returns DomNode"):
    var success = false
    val node = Clipboard.copyOnClick("text", onSuccess = () => success = true, onFailure = _ => ())
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.copyOnClickDynamic returns DomNode"):
    val node = Clipboard.copyOnClickDynamic(() => "dynamic text")
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard.copyOnClickDynamic with callbacks returns DomNode"):
    var success = false
    val node    = Clipboard.copyOnClickDynamic(
      () => "dynamic text",
      onSuccess = () => success = true,
      onFailure = _ => ()
    )
    node shouldMatch { case _: DomNode =>
    }

  test("Clipboard handlers can be attached to elements"):
    val elem = div(
      Clipboard.onCopy(text => ()),
      Clipboard.onCut(text => ()),
      Clipboard.onPaste(text => ()),
      input(placeholder -> "Test input")
    )
    elem shouldMatch { case _: RxElement =>
    }

  test("Clipboard.copyOnClick can be attached to button"):
    val elem = button(Clipboard.copyOnClick("Hello, World!"), "Copy")
    elem shouldMatch { case _: RxElement =>
    }

  test("Clipboard event handlers render correctly"):
    val elem           = div(Clipboard.onPaste(_ => ()))
    val (node, cancel) = DomRenderer.createNode(elem)

    node match
      case e: dom.Element =>
        // The element should exist
        e.tagName.toLowerCase shouldBe "div"
      case _ =>
        fail("Expected Element")

    cancel.cancel

end ClipboardTest
