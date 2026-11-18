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

import wvlet.uni.util.ThreadUtil

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
  * JVM-specific scheduler implementations using work-stealing thread pools.
  */
private[rx] object schedulerCompat:

  /**
    * Default work-stealing scheduler sized to available processors.
    */
  lazy val defaultScheduler: RxScheduler = createScheduler(Runtime.getRuntime.availableProcessors())

  /**
    * Scheduler for blocking operations: virtual threads on JDK 24+, a cached platform thread pool
    * on older JVMs.
    */
  lazy val blockingScheduler: RxScheduler =
    if useVirtualThreads(Runtime.version().feature()) then
      virtualThreadBlockingScheduler()
    else
      platformThreadBlockingScheduler()

  /**
    * Whether the blocking scheduler runs on virtual threads for the given JDK feature version.
    *
    * JDK 21-23 pin a virtual thread to its carrier while it blocks inside `synchronized`, and once
    * every carrier is pinned all virtual threads stop. JDK 24 (JEP 491) removes that pinning, so
    * virtual threads are used only from JDK 24.
    */
  private[rx] def useVirtualThreads(jdkFeatureVersion: Int): Boolean = jdkFeatureVersion >= 24

  /**
    * A blocking scheduler that runs each task on a new virtual thread. Requires JDK 21+.
    */
  private[rx] def virtualThreadBlockingScheduler(): RxScheduler =
    new BlockingScheduler(
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rx-blocking-", 1).factory())
    )

  /**
    * A blocking scheduler backed by a cached pool of platform daemon threads.
    */
  private[rx] def platformThreadBlockingScheduler(): RxScheduler =
    new BlockingScheduler(
      Executors.newCachedThreadPool(ThreadUtil.newDaemonThreadFactory("rx-blocking"))
    )

  /**
    * Create a work-stealing scheduler with the specified parallelism.
    */
  def createScheduler(parallelism: Int): RxScheduler = new WorkStealingScheduler(parallelism)

  /**
    * Work-stealing scheduler based on ForkJoinPool.
    *
    * This scheduler is optimized for many short-lived tasks (fibers). It uses FIFO ordering within
    * worker queues for better latency characteristics.
    */
  private class WorkStealingScheduler(val parallelism: Int) extends RxScheduler:
    // ForkJoinPool with asyncMode=true for FIFO task ordering
    private val pool =
      new ForkJoinPool(
        parallelism,
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null, // UncaughtExceptionHandler
        true  // asyncMode = true for FIFO ordering
      )

    // Separate scheduler for delayed tasks
    private val delayScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
      1,
      ThreadUtil.newDaemonThreadFactory("rx-delay-scheduler")
    )

    override def execute(task: Runnable): Unit = pool.execute(task)

    override def schedule(delay: Long, unit: TimeUnit)(task: => Unit): Cancelable =
      val cancelled = new AtomicBoolean(false)
      val future    = delayScheduler.schedule(
        new Runnable:
          override def run(): Unit =
            if !cancelled.get() then
              // Execute the task on the work-stealing pool
              pool.execute(() => task)
        ,
        delay,
        unit
      )
      Cancelable { () =>
        cancelled.set(true)
        future.cancel(false)
      }

    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit)(
        task: => Unit
    ): Cancelable =
      val cancelled = new AtomicBoolean(false)
      val future    = delayScheduler.scheduleAtFixedRate(
        new Runnable:
          override def run(): Unit =
            if !cancelled.get() then
              pool.execute(() => task)
        ,
        initialDelay,
        period,
        unit
      )
      Cancelable { () =>
        cancelled.set(true)
        future.cancel(false)
      }

    override def shutdown(): Unit =
      pool.shutdown()
      delayScheduler.shutdown()

  end WorkStealingScheduler

  /**
    * Scheduler for blocking operations that would otherwise starve the compute pool. The executor
    * starts a thread (virtual or platform) as tasks need one, so parallelism is unbounded.
    */
  private class BlockingScheduler(pool: ExecutorService) extends RxScheduler:

    private val delayScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
      1,
      ThreadUtil.newDaemonThreadFactory("rx-blocking-delay")
    )

    // Parallelism is effectively unlimited for blocking operations
    override val parallelism: Int = Int.MaxValue

    override def execute(task: Runnable): Unit = pool.execute(task)

    override def schedule(delay: Long, unit: TimeUnit)(task: => Unit): Cancelable =
      val cancelled = new AtomicBoolean(false)
      val future    = delayScheduler.schedule(
        new Runnable:
          override def run(): Unit =
            if !cancelled.get() then
              pool.execute(() => task)
        ,
        delay,
        unit
      )
      Cancelable { () =>
        cancelled.set(true)
        future.cancel(false)
      }

    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit)(
        task: => Unit
    ): Cancelable =
      val cancelled = new AtomicBoolean(false)
      val future    = delayScheduler.scheduleAtFixedRate(
        new Runnable:
          override def run(): Unit =
            if !cancelled.get() then
              pool.execute(() => task)
        ,
        initialDelay,
        period,
        unit
      )
      Cancelable { () =>
        cancelled.set(true)
        future.cancel(false)
      }

    override def shutdown(): Unit =
      pool.shutdown()
      delayScheduler.shutdown()

  end BlockingScheduler

end schedulerCompat
