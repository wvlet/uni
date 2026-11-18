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
package wvlet.uni.rx

import java.util.concurrent.TimeUnit

/**
  * A scheduler for executing Rx computations.
  *
  * RxScheduler provides a platform-independent abstraction for scheduling work. On JVM, it uses a
  * work-stealing thread pool for efficient fiber-like execution. On Scala.js, it uses the microtask
  * queue. On Scala Native, it uses a fixed-size thread pool.
  */
trait RxScheduler:

  /**
    * Execute a task on this scheduler.
    */
  def execute(task: Runnable): Unit

  /**
    * Schedule a task to run after a delay.
    */
  def schedule(delay: Long, unit: TimeUnit)(task: => Unit): Cancelable

  /**
    * Schedule a task to run periodically.
    */
  def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit)(
      task: => Unit
  ): Cancelable

  /**
    * The parallelism level of this scheduler (number of worker threads on JVM, 1 on JS).
    */
  def parallelism: Int

  /**
    * Shutdown this scheduler, releasing resources.
    */
  def shutdown(): Unit

object RxScheduler:

  /**
    * Get the default scheduler for the current platform.
    */
  def default: RxScheduler = schedulerCompat.defaultScheduler

  /**
    * Create a scheduler with the specified parallelism level. On Scala.js, parallelism is always 1.
    */
  def apply(parallelism: Int): RxScheduler = schedulerCompat.createScheduler(parallelism)

  /**
    * The scheduler for blocking operations, such as file and network I/O. On JDK 24+ each task runs
    * on its own virtual thread; on older JVMs it uses a cached thread pool that grows as needed.
    * Scala.js and Scala Native use their own platform schedulers.
    */
  def blocking: RxScheduler = schedulerCompat.blockingScheduler

end RxScheduler
