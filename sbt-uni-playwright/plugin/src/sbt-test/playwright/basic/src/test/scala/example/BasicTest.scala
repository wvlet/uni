package example

import org.scalajs.dom

class BasicTest extends munit.FunSuite:

  test("runs in a real browser with a DOM (proves it is not Node/jsdom-less)"):
    val el = dom.document.createElement("div")
    el.textContent = "hello from playwright"
    assertEquals(el.textContent, "hello from playwright")

  test("window.matchMedia exists (a real browser API jsdom lacks)"):
    val mql = dom.window.matchMedia("(min-width: 0px)")
    assert(mql.matches)

  test("async assertion completes over the com channel"):
    import scala.concurrent.ExecutionContext.Implicits.global
    scala.concurrent.Future(1 + 1).map(v => assertEquals(v, 2))

end BasicTest
