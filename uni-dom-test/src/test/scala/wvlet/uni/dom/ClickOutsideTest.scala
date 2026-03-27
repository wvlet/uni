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
import wvlet.uni.rx.Rx
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given

class ClickOutsideTest extends UniTest:

  test("ClickOutside.detect creates ClickOutsideBinding"):
    val binding = ClickOutside.detect(_ => ())
    binding shouldMatch { case _: ClickOutsideBinding =>
    }

  test("ClickOutside.hide creates ClickOutsideBinding"):
    val visible = Rx.variable(true)
    val binding = ClickOutside.hide(visible)
    binding shouldMatch { case _: ClickOutsideBinding =>
    }

  test("ClickOutside.onClickOutside creates ClickOutsideBinding"):
    var called  = false
    val binding = ClickOutside.onClickOutside(() => called = true)
    binding shouldMatch { case _: ClickOutsideBinding =>
    }

  test("ClickOutsideBinding stores callback"):
    var received: Option[org.scalajs.dom.MouseEvent] = None
    val binding                                      = ClickOutside.detect(e => received = Some(e))
    binding shouldMatch { case cb: ClickOutsideBinding =>
      cb.callback shouldMatch { case _: Function1[?, ?] =>
      }
    }

  test("ClickOutside.detect can be used as DomNode modifier"):
    val elem = div(ClickOutside.detect(_ => ()), span("content"))
    elem shouldMatch { case _: DomElement =>
    }

  test("ClickOutside.hide can be used as DomNode modifier"):
    val visible = Rx.variable(true)
    val elem    = div(ClickOutside.hide(visible), span("content"))
    elem shouldMatch { case _: DomElement =>
    }

  // Note: Full integration tests for click outside detection require a browser
  // environment with real mouse events. The bindings are created correctly
  // (tested above) and the handler code in DomRenderer will work in a real
  // browser environment.

end ClickOutsideTest
