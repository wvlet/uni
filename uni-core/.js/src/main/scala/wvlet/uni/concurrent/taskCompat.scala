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
package wvlet.uni.concurrent

import scala.scalajs.js

/**
  * Scala.js implementation of [[Task]].
  *
  * The body runs cooperatively on the event loop (queued via a resolved Promise), not on an OS
  * thread — JavaScript has no portable threading primitive that works in both Node and the browser.
  * Cancellation, progress reporting, and `awaitRx` work the same as on JVM. Blocking `await()` is
  * intentionally unsupported: the event loop cannot block on local progress without a
  * `worker_threads`-style shared-memory channel, which is a Node-only opt-in we may add later (see
  * uni#552).
  *
  * Practical implications for body authors:
  *   - The body runs synchronously once dispatched. A long-running CPU loop will block the event
  *     loop just as it would in any other JS code. Yield with `await`/Promises for naturally async
  *     workloads (HTTP polling, `worker_threads`-backed sync HTTP, …).
  *   - `Task.run` returns synchronously, so a caller may call `cancel()` (or install `awaitRx`
  *     subscribers) before the body starts.
  */
private[concurrent] object taskCompat:

  def run(body: TaskContext => Unit): Task =
    val task = new JsTaskImpl()
    task.start(body)
    task

  private class JsTaskImpl extends TaskImpl:

    override protected def scheduleBody(body: TaskContext => Unit): Unit =
      // Microtask scheduling: matches `schedulerCompat.execute` in uni-core. The body sees any
      // `cancel()` made between `Task.run` returning and this turn firing.
      js.Promise.resolve(()).`then`(_ => runBody(body))

    override protected def awaitTerminal(): Unit =
      throw new UnsupportedOperationException(
        "Task.await() is unsupported on Scala.js; use Task.awaitRx instead."
      )

  end JsTaskImpl

end taskCompat
