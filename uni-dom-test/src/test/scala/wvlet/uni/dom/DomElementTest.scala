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
import wvlet.uni.dom.all.given
import scala.language.implicitConversions

class DomElementTest extends UniTest:

  test("create div element"):
    val d: DomElement = div
    d.name shouldBe "div"
    d.namespace shouldBe DomNamespace.xhtml
    d.modifiers.isEmpty shouldBe true

  test("create div with class attribute"):
    val d = div(cls -> "main")
    d.modifiers.flatten.size shouldBe 1
    d.modifiers.flatten.head shouldMatch { case a: DomAttribute =>
      a.name shouldBe "class"
      a.v shouldBe "main"
    }

  test("create div with multiple attributes"):
    val d = div(cls -> "container", id -> "main")
    d.modifiers.flatten.size shouldBe 2

  test("nest elements"):
    val d = div(h1("Title"), p("Content"))
    d.modifiers.flatten.size shouldBe 2

  test("deeply nested elements"):
    val d = div(
      header(h1("Header")),
      main(p("Paragraph 1"), p("Paragraph 2")),
      footer(span("Footer"))
    )
    d.modifiers.flatten.size shouldBe 3

  test("create element with text content"):
    val d = span("Hello World")
    d.modifiers.flatten.size shouldBe 1

  test("create element with mixed content"):
    val d = p("Hello ", strong("World"), "!")
    d.modifiers.flatten.size shouldBe 3

  test("create form elements"):
    val f = form(
      action -> "/submit",
      method -> "POST",
      input(tpe  -> "text", name     -> "username", placeholder -> "Username"),
      input(tpe  -> "password", name -> "password"),
      button(tpe -> "submit", "Login")
    )
    f.modifiers.flatten.size shouldBe 5

  test("create table structure"):
    val t = table(
      thead(tr(th("Name"), th("Value"))),
      tbody(tr(td("A"), td("1")), tr(td("B"), td("2")))
    )
    t.modifiers.flatten.size shouldBe 2

  test("create element with boolean attributes"):
    val i = input(tpe -> "checkbox", checked -> true, disabled)
    i.modifiers.flatten.size shouldBe 3

  test("create link element"):
    val l = a(href -> "https://example.com", target -> "_blank", "Example")
    l.modifiers.flatten.size shouldBe 3

  test("create image element"):
    val i = img(src -> "/image.png", alt -> "Description", width -> "100", height -> "100")
    i.modifiers.flatten.size shouldBe 4

  test("conditional rendering with when"):
    val visible = true
    val d       = div(when(visible, span("Visible")))
    d.modifiers.flatten.size shouldBe 1

  test("conditional rendering with when false"):
    val visible = false
    val d       = div(when(visible, span("Visible")))
    d.modifiers.flatten.head shouldBe DomNode.empty

  test("element chaining"):
    val d = div.add(cls -> "a").add(id -> "b").add(span("content"))
    d.modifiers.flatten.size shouldBe 3

  test("SVG namespace"):
    val s = HtmlTags.tagOf("svg", DomNamespace.svg)
    s.namespace shouldBe DomNamespace.svg

  test("data attributes"):
    val d = div(data("user-id") -> "123", data("role") -> "admin")
    d.modifiers.flatten.size shouldBe 2
    d.modifiers.flatten.head shouldMatch { case a: DomAttribute =>
      a.name shouldBe "data-user-id"
    }

  test("append class"):
    val d = div(cls -> "base", cls += "additional")
    d.modifiers.flatten.size shouldBe 2
    d.modifiers.flatten.last shouldMatch { case a: DomAttribute =>
      a.append shouldBe true
    }

end DomElementTest
