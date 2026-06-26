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

  /**
    * Assert that `arm` completes its promise with `true` from a RAF callback. A setTimeout fallback
    * fails the test fast instead of hanging if RAF never fires.
    */
  private def firesWithin5s(arm: Promise[Boolean] => Unit) =
    val fired = Promise[Boolean]()
    arm(fired)
    val handle = dom.window.setTimeout(() => fired.trySuccess(false), 5000)
    fired
      .future
      .map { result =>
        dom.window.clearTimeout(handle)
        result shouldBe true
      }

  test("AnimationFrame object exists"):
    AnimationFrame shouldNotBe null

  test("once schedules a callback that fires on the next frame"):
    firesWithin5s(fired => AnimationFrame.once(fired.trySuccess(true)))

  test("loop invokes the callback and can be stopped"):
    firesWithin5s { fired =>
      var loop: Cancelable = Cancelable.empty
      loop = AnimationFrame.loop { _ =>
        fired.trySuccess(true)
        loop.cancel
      }
    }

  test("once can be cancelled before it fires without error"):
    val c = AnimationFrame.once {
      throw new AssertionError("cancelled callback must not run")
    }
    c.cancel

end AnimationFrameTest
