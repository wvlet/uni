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

import wvlet.uni.util.ThreadUtil

import java.util.concurrent.CountDownLatch

/**
  * Native worker (Scala Native multithreaded): runs the body on a daemon thread; the gate is a
  * one-shot latch. Identical to the JVM impl — `java.lang.Thread` / `CountDownLatch` are both
  * available on Scala Native 0.5, as the Native HTTP server already relies on.
  */
private[control] object BackgroundTaskCompat:
  private val threadFactory = ThreadUtil.newDaemonThreadFactory("uni-task")

  def runWorker(body: () => Unit): Unit = threadFactory.newThread(() => body()).start()

  def newGate(): Gate = LatchGate()

private[control] class LatchGate extends Gate:
  private val latch           = CountDownLatch(1)
  override def await(): Unit  = latch.await()
  override def signal(): Unit = latch.countDown()
