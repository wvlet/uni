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
package wvlet.uni.test.spi

import wvlet.uni.log.LogFormatter.SourceCodeLogFormatter
import wvlet.uni.log.Logger

import java.util.function.Supplier
import java.util.logging as jl

/**
  * Sends log records emitted while a suite runs to the sbt loggers of its task.
  *
  * sbt buffers what a task writes to its loggers and prints it when the suite ends, so suites
  * running in parallel don't interleave. Log records written directly to the console bypass that
  * buffer and appear apart from the test results they belong to. Routing them through the same
  * loggers keeps each record next to the result line of the test that emitted it.
  *
  * Only records from the thread running the suite are routed. Records from other threads (a
  * server's worker pool, a Future on the global pool) go to the original handlers, since a pool
  * thread cannot be attributed to a single suite.
  */
private[test] object TestLogCapture:
  private val currentLoggers = ThreadLocal[Array[sbt.testing.Logger]]()

  /**
    * Wraps the root logger's handlers. Each runner's class loader has its own copy of this class,
    * so a runner of another project in the same JVM may wrap this router in its own. After
    * `remove()`, the router passes records through and returns the handlers it wrapped from
    * `get()`, a JDK-typed accessor that a runner of any class loader can use to unwrap it.
    */
  private class RoutingHandler(original: Array[jl.Handler])
      extends jl.Handler,
        Supplier[Array[jl.Handler]]:
    @volatile
    private var removed = false

    def remove(): Unit = removed = true

    override def get(): Array[jl.Handler] =
      if removed then
        original
      else
        null

    override def publish(record: jl.LogRecord): Unit =
      val loggers = currentLoggers.get()
      if removed || loggers == null then
        original.foreach(_.publish(record))
      else
        val msg   = SourceCodeLogFormatter.format(record)
        val level = record.getLevel.intValue()
        if level >= jl.Level.SEVERE.intValue() then
          loggers.foreach(_.error(msg))
        else if level >= jl.Level.WARNING.intValue() then
          loggers.foreach(_.warn(msg))
        else
          loggers.foreach(_.info(msg))

    override def flush(): Unit = original.foreach(_.flush())
    override def close(): Unit = original.foreach(_.close())

  end RoutingHandler

  /**
    * Replace removed routers, of any class loader, with the handlers they wrapped
    */
  private def unwrapRemoved(handlers: Seq[jl.Handler]): Seq[jl.Handler] = handlers.flatMap { h =>
    h match
      case s: Supplier[?] =>
        s.get() match
          case wrapped: Array[jl.Handler] =>
            unwrapRemoved(wrapped.toSeq)
          case _ =>
            Seq(h)
      case _ =>
        Seq(h)
  }

  private def setHandlers(root: Logger, handlers: Seq[jl.Handler]): Unit =
    if handlers != root.getHandlers then
      root.clearHandlers
      handlers.foreach(root.addHandler)

  /**
    * Install the routing handler on the root logger. Returns an action that removes it again, or a
    * no-op when a routing handler is already installed (e.g., by an enclosing runner).
    */
  def install(): () => Unit = synchronized {
    val root     = Logger.rootLogger
    val handlers = unwrapRemoved(root.getHandlers)
    if handlers.exists(_.isInstanceOf[RoutingHandler]) then
      setHandlers(root, handlers)
      () => ()
    else
      val router = RoutingHandler(handlers.toArray)
      setHandlers(root, Seq(router))
      () =>
        synchronized {
          router.remove()
          // Unwraps this router, and any router removed earlier while this one wrapped it. Handlers
          // that test code set in the meantime are kept.
          setHandlers(root, unwrapRemoved(root.getHandlers))
        }
  }

  /**
    * Route log records emitted on the current thread to `loggers` while `body` runs
    */
  def withLoggers[A](loggers: Array[sbt.testing.Logger])(body: => A): A =
    val prev = currentLoggers.get()
    currentLoggers.set(loggers)
    try body
    finally currentLoggers.set(prev)

end TestLogCapture
