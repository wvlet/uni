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

import java.util.concurrent.CountDownLatch
import wvlet.uni.util.ThreadUtil

private[concurrent] object taskCompat:

  private lazy val threadFactory = ThreadUtil.newDaemonThreadFactory("uni-task")

  def run(body: TaskContext => Unit): Task =
    val task = new JvmTaskImpl()
    task.start(body)
    task

  private class JvmTaskImpl extends TaskImpl:
    private val latch = new CountDownLatch(1)
    // Set before the worker starts running, read by onCancelRequested for Thread.interrupt.
    @volatile
    private var worker: Thread = null

    override protected def scheduleBody(body: TaskContext => Unit): Unit =
      val t = threadFactory.newThread { () =>
        try runBody(body)
        finally latch.countDown()
      }
      worker = t
      t.start()

    override protected def awaitTerminal(): Unit =
      latch.await()
      val cause = terminalFailure
      if cause != null then
        throw cause

    override protected def onCancelRequested(): Unit =
      val t = worker
      if t != null then
        // Interrupt unwedges blocking JDK calls (Thread.sleep, blocking IO, …) so the body's
        // next checkCancelled — or the JDK call itself — exits promptly.
        t.interrupt()

  end JvmTaskImpl

end taskCompat
