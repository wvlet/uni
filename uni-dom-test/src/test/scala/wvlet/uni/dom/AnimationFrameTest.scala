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
import wvlet.uni.rx.Cancelable

import scala.concurrent.{ExecutionContext, Promise}

class AnimationFrameTest extends UniTest:

  private given ExecutionContext = org.scalajs.macrotaskexecutor.MacrotaskExecutor

  // These run in a real headless Chromium (Playwright), so requestAnimationFrame is
  // available and the callbacks actually fire — not just compile.

  test("AnimationFrame object exists"):
    AnimationFrame shouldNotBe null

  test("once schedules a callback that fires on the next frame"):
    val fired = Promise[Boolean]()
    AnimationFrame.once {
      fired.trySuccess(true)
    }
    // Safety net so the test fails fast instead of hanging if RAF never fires.
    dom.window.setTimeout(() => fired.trySuccess(false), 5000)
    fired.future.map(_ shouldBe true)

  test("loop invokes the callback and can be stopped"):
    val fired            = Promise[Boolean]()
    var loop: Cancelable = Cancelable.empty
    loop = AnimationFrame.loop { _ =>
      fired.trySuccess(true)
      loop.cancel
    }
    dom.window.setTimeout(() => fired.trySuccess(false), 5000)
    fired.future.map(_ shouldBe true)

  test("once can be cancelled before it fires without error"):
    val c = AnimationFrame.once {
      throw new AssertionError("cancelled callback must not run")
    }
    c.cancel

end AnimationFrameTest
