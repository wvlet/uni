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
package example

import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Exercises the trusted-input and viewport page commands through the raw protocol. */
class InputTest extends munit.FunSuite:

  private def call(cmd: String, args: js.Dynamic): Future[js.Any] = js
    .Dynamic
    .global
    .__uniPlaywright
    .call(cmd, args)
    .asInstanceOf[js.Promise[js.Any]]
    .toFuture

  /** Append `html` in a fresh container and return the container. */
  private def mount(html: String): dom.Element =
    val c = dom.document.createElement("div")
    c.innerHTML = html
    dom.document.body.appendChild(c)
    c

  private def input(id: String): dom.html.Input =
    dom.document.getElementById(id).asInstanceOf[dom.html.Input]

  private def failsWith(f: Future[?], fragment: String): Future[Unit] = f.transform {
    case scala.util.Failure(e) if e.getMessage.contains(fragment) =>
      scala.util.Success(())
    case other =>
      scala.util.Failure(AssertionError(s"Expected failure containing '${fragment}', got ${other}"))
  }

  test("click sends a trusted event and moves focus"):
    mount("""<input id="click-in">""")
    var trusted = false
    input("click-in").addEventListener("click", (e: dom.Event) => trusted = e.isTrusted)
    call("click", js.Dynamic.literal(selector = "#click-in")).map { _ =>
      assert(trusted, "click event should be trusted")
      assertEquals(dom.document.activeElement.id, "click-in")
    }

  test("type inserts text into the focused element and press Enter submits the form"):
    mount("""<form id="type-form"><input id="type-in"></form>""")
    var submitted = false
    dom
      .document
      .getElementById("type-form")
      .addEventListener(
        "submit",
        (e: dom.Event) =>
          e.preventDefault()
          submitted = true
      )
    for
      _ <- call("click", js.Dynamic.literal(selector = "#type-in"))
      _ <- call("type", js.Dynamic.literal(text = "hello"))
      _ = assertEquals(input("type-in").value, "hello")
      _ <- call("press", js.Dynamic.literal(key = "Enter"))
    yield assert(submitted, "Enter should submit the form")

  test("press accepts key combinations"):
    mount("""<input id="combo-in" value="abc">""")
    var seen = ""
    input("combo-in").addEventListener(
      "keydown",
      (e: dom.KeyboardEvent) =>
        if e.shiftKey && e.key == "ArrowLeft" then
          seen = "Shift+ArrowLeft"
    )
    for
      _ <- call("click", js.Dynamic.literal(selector = "#combo-in"))
      _ <- call("press", js.Dynamic.literal(key = "Shift+ArrowLeft"))
    yield assertEquals(seen, "Shift+ArrowLeft")

  test("fill replaces the value"):
    mount("""<input id="fill-in" value="old">""")
    call("fill", js.Dynamic.literal(selector = "#fill-in", text = "new")).map { _ =>
      assertEquals(input("fill-in").value, "new")
    }

  test("hover makes :hover match"):
    mount("""<div id="hover-box" style="width:40px;height:40px;background:gray"></div>""")
    call("hover", js.Dynamic.literal(selector = "#hover-box")).map { _ =>
      assert(dom.document.getElementById("hover-box").matches(":hover"))
    }

  test("setViewportSize resizes the page and returns the previous size"):
    val narrow = dom.window.matchMedia("(max-width: 600px)")
    assert(!narrow.matches)
    for
      prev <- call("setViewportSize", js.Dynamic.literal(width = 500, height = 400))
      p = prev.asInstanceOf[js.Dynamic]
      _ = assertEquals(p.width.asInstanceOf[Int], 1280)
      _ = assertEquals(p.height.asInstanceOf[Int], 720)
      _ = assertEquals(dom.window.innerWidth.toInt, 500)
      _ = assert(narrow.matches, "media query should follow the new viewport")
      _ <- call("setViewportSize", js.Dynamic.literal(width = 1280, height = 720))
    yield assert(!narrow.matches)

  test("input on a missing element times out"):
    failsWith(call("click", js.Dynamic.literal(selector = "#nope", timeout = 200)), "Timeout")

  test("input on an ambiguous selector is rejected"):
    mount("""<button class="dup">a</button><button class="dup">b</button>""")
    failsWith(call("click", js.Dynamic.literal(selector = ".dup")), "strict mode violation")

  test("rejects invalid arguments"):
    for
      _ <- failsWith(call("click", js.Dynamic.literal()), "Missing 'selector'")
      _ <- failsWith(call("setViewportSize", js.Dynamic.literal(width = 0, height = 400)), "width")
    yield ()

end InputTest
