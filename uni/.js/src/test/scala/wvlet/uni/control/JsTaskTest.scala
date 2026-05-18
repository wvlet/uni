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
package wvlet.uni.control

import java.util.concurrent.atomic.AtomicInteger
import wvlet.uni.test.UniTest

/**
  * Scala.js-specific Task tests — blocking-`await` rejection plus the deterministic
  * "cancel-before-the-body-starts" path that microtask scheduling gives us.
  */
class JsTaskTest extends UniTest:

  test("await() throws UnsupportedOperationException on Scala.js") {
    val task = Task.run { _ =>
      ()
    }
    val caught =
      try
        task.await()
        null
      catch
        case e: UnsupportedOperationException =>
          e
    (caught != null) shouldBe true
  }

  test("cancel before the body's microtask fires skips execution") {
    val counter = new AtomicInteger(0)
    val task    = Task.run { ctx =>
      // Deterministic on Scala.js: `cancel()` below runs before this microtask, so
      // checkCancelled throws and the body never touches `counter`. JVM/Native can't make
      // this guarantee.
      ctx.checkCancelled()
      counter.set(99)
    }
    task.cancel()
    task
      .awaitRx
      .recover { case _: InterruptedException =>
      // expected
      }
      .map { _ =>
        counter.get() shouldBe 0
        task.state shouldBe Task.State.Cancelled
      }
  }

end JsTaskTest
