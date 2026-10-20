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
package wvlet.uni.test.playwright

import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

import scala.scalajs.js
import scala.util.{Failure, Success}

class PlaywrightTest extends UniTest:

  private def failsWith[A](rx: Rx[A], fragment: String): Rx[Unit] = rx.transform {
    case Failure(e) =>
      e.getMessage shouldContain fragment
    case Success(v) =>
      fail(s"Expected failure containing '${fragment}', got ${v}")
  }

  /**
    * Run `body` with a stand-in `__uniPlaywright` bridge that answers commands with `respond` and
    * records them, then remove it. Node has no Playwright, so this checks the client's side of the
    * protocol.
    */
  private def withBridge[A](respond: (String, js.Dynamic) => js.Promise[js.Any])(
      body: js.Array[String] => Rx[A]
  ): Rx[A] =
    val calls  = js.Array[String]()
    val bridge = js
      .Dynamic
      .literal(
        version = Playwright.protocolVersion,
        call =
          (cmd: String, args: js.Dynamic) =>
            calls.push(cmd)
            respond(cmd, args)
      )
    js.Dynamic.global.globalThis.__uniPlaywright = bridge
    body(calls).transformRx { result =>
      js.special.delete(js.Dynamic.global.globalThis, "__uniPlaywright")
      Rx.fromTry(result)
    }

  test("is unavailable outside the Playwright JSEnv") {
    Playwright.isAvailable shouldBe false
  }

  test("calls fail with a message naming the required JSEnv") {
    Seq[Rx[?]](
      Playwright.screenshot("x"),
      Playwright.click("#a"),
      Playwright.typeText("t"),
      Playwright.setViewportSize(100, 100)
    ).map { rx =>
        rx.transform {
          case Failure(e) =>
            e shouldMatch { case _: IllegalStateException =>
            }
            e.getMessage shouldContain "uni-jsenv-playwright"
          case Success(v) =>
            fail(s"Expected a failure, got ${v}")
        }
      }
      .reduce((a, b) => a.flatMap(_ => b))
  }

  test("commands are sent only when the Rx runs") {
    withBridge((_, _) => js.Promise.resolve[js.Any](null)) { calls =>
      val rx = Playwright.click("#a").flatMap(_ => Playwright.press("Enter"))
      calls.length shouldBe 0
      rx.map(_ => calls.toList shouldBe List("click", "press"))
    }
  }

  test("withViewportSize restores the previous size even when the body fails") {
    val sizes = js.Array[String]()
    withBridge { (cmd, args) =>
      sizes.push(s"${args.width}x${args.height}")
      js.Promise.resolve[js.Any](js.Dynamic.literal(width = 1280, height = 720))
    } { calls =>
      failsWith(
        Playwright.withViewportSize(400, 300)(Rx.exception(IllegalStateException("boom"))),
        "boom"
      ).map(_ => sizes.toList shouldBe List("400x300", "1280x720"))
    }
  }

  test("explains an unknown command from an older JSEnv") {
    withBridge((cmd, _) =>
      js.Promise
        .reject(js.JavaScriptException(js.Error(s"${cmd} failed: Unknown page command: ${cmd}")))
        .asInstanceOf[js.Promise[js.Any]]
    ) { _ =>
      failsWith(Playwright.hover("#a"), "requires a newer uni-jsenv-playwright")
    }
  }

end PlaywrightTest
