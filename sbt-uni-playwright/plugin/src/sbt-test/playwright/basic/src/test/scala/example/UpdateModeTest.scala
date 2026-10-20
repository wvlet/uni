package example

import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** Run only with `uniPlaywrightUpdateSnapshots := true` (see the scripted `test` script). */
class UpdateModeTest extends munit.FunSuite:

  test("update mode overwrites the golden instead of comparing"):
    val el = dom.document.createElement("div")
    el.id = "upd"
    dom.document.body.appendChild(el)
    def expect() = js
      .Dynamic
      .global
      .__uniPlaywright
      .call("expectScreenshot", js.Dynamic.literal(name = "upd", selector = "#upd"))
      .asInstanceOf[js.Promise[js.Any]]
      .toFuture
    el.setAttribute("style", "width:40px;height:40px;background:blue")
    for
      a <- expect()
      _ = el.setAttribute("style", "width:40px;height:40px;background:red")
      b <- expect()
    yield
      assertEquals(a.toString, "updated")
      assertEquals(b.toString, "updated")

end UpdateModeTest
