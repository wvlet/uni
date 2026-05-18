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

import java.util.concurrent.CountDownLatch
import wvlet.uni.util.ThreadUtil

/**
  * Scala Native uses the same JDK threading primitives as JVM, so this mirrors the JVM impl.
  * `Thread.interrupt` semantics against libc blocking syscalls are weaker than HotSpot's; bodies
  * that need unblocking on cancel should still observe `isCancelled` at loop checkpoints.
  */
private[control] object taskCompat:

  private lazy val threadFactory = ThreadUtil.newDaemonThreadFactory("uni-task")

  def run(body: TaskContext => Unit): Task =
    val task = new NativeTaskImpl()
    task.scheduleBody(body)
    task

  def runRegistered(taskId: String): Task = run(Task.lookup(taskId))

  private class NativeTaskImpl extends TaskImpl:
    private val latch = new CountDownLatch(1)
    @volatile
    private var worker: Thread = null

    override def scheduleBody(body: TaskContext => Unit): Unit =
      val t = threadFactory.newThread { () =>
        try runBody(body)
        finally latch.countDown()
      }
      worker = t
      t.start()

    override def awaitTerminal(): Unit =
      latch.await()
      val cause = terminalFailure
      if cause != null then
        throw cause

    override def onCancelRequested(): Unit =
      val t = worker
      if t != null then
        t.interrupt()

  end NativeTaskImpl

end taskCompat
