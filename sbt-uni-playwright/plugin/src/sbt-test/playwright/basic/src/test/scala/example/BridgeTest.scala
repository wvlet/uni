package example

import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Exercises the raw `__uniPlaywright` page-command protocol. */
class BridgeTest extends munit.FunSuite:

  private def call(cmd: String, args: js.Dynamic): Future[js.Any] = js
    .Dynamic
    .global
    .__uniPlaywright
    .call(cmd, args)
    .asInstanceOf[js.Promise[js.Any]]
    .toFuture

  private def box(id: String, color: String): Unit =
    val el = Option(dom.document.getElementById(id)).getOrElse {
      val e = dom.document.createElement("div")
      e.id = id
      dom.document.body.appendChild(e)
      e
    }
    el.setAttribute("style", s"width:40px;height:40px;background:${color}")

  private def failsWith(f: Future[?], fragment: String): Future[Unit] = f.transform {
    case scala.util.Failure(e) if e.getMessage.contains(fragment) =>
      scala.util.Success(())
    case other =>
      scala.util.Failure(AssertionError(s"Expected failure containing '${fragment}', got ${other}"))
  }

  test("exposes the protocol version"):
    assertEquals(js.Dynamic.global.__uniPlaywright.version.asInstanceOf[Int], 1)

  test("screenshot writes a PNG and returns its path"):
    box("shot", "blue")
    call("screenshot", js.Dynamic.literal(name = "shot", selector = "#shot")).map { p =>
      assert(p.toString.endsWith("shot.png"), p)
    }

  test("expectScreenshot: missing golden fails, then matches, then detects a change"):
    box("golden", "green")
    val args = js.Dynamic.literal(name = "golden", selector = "#golden")
    for
      _ <- failsWith(call("expectScreenshot", args), "No golden image")
      r <- call("expectScreenshot", args)
      _ = assertEquals(r.toString, "matched")
      _ = box("golden", "red")
      _ <- failsWith(call("expectScreenshot", args), "does not match")
    yield ()

  test("rejects names that escape the output directory"):
    failsWith(call("screenshot", js.Dynamic.literal(name = "../evil")), "Invalid name")

  test("rejects unknown commands"):
    failsWith(call("noSuchCommand", js.Dynamic.literal()), "Unknown page command")

end BridgeTest
