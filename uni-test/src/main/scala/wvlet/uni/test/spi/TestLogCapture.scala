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
  *
  * The same handler serves `UniTest.captureLogs`: while a capture is open, records it owns are kept
  * in the capture instead of being routed or printed.
  */
private[test] object TestLogCapture:
  private val currentLoggers = ThreadLocal[Array[sbt.testing.Logger]]()
  // Set on threads that run a suite, so their records are never taken by another suite's capture
  private val suiteThread = ThreadLocal[java.lang.Boolean]()

  @volatile
  private var captures: List[Capture] = Nil

  /**
    * Log records captured by `UniTest.captureLogs` while its block runs
    *
    * @param owner
    *   the thread that opened the capture
    * @param loggerName
    *   capture only this logger and its children, or every logger when None
    */
  final class Capture(
      private[TestLogCapture] val owner: Thread,
      loggerName: Option[String],
      private[TestLogCapture] val router: RoutingHandler,
      release: () => Unit
  ):
    private val buf    = scala.collection.mutable.ArrayBuffer.empty[jl.LogRecord]
    private var closed = false // guarded by buf

    private[TestLogCapture] def accepts(record: jl.LogRecord): Boolean = loggerName.forall { name =>
      val n = record.getLoggerName
      n != null && (n == name || n.startsWith(s"${name}."))
    }

    /**
      * Returns false when the capture has closed, so a record arriving late is not lost
      */
    private[TestLogCapture] def add(record: jl.LogRecord): Boolean = buf.synchronized {
      if !closed then
        buf += record
      !closed
    }

    /**
      * Stop capturing. With `replay`, the captured records are sent to the handlers they would have
      * reached without the capture, so a failing test still shows them.
      */
    def close(replay: Boolean): Seq[jl.LogRecord] =
      TestLogCapture.synchronized {
        captures = captures.filterNot(_ eq this)
      }
      val captured = buf.synchronized {
        closed = true
        buf.toSeq
      }
      try
        // Through publish, so an enclosing capture still takes the records
        if replay then
          captured.foreach(router.publish)
      finally release()
      captured

  end Capture

  /**
    * Start capturing log records from the current thread and from threads that run no suite (server
    * workers, Future pools). Records from threads running another suite are never captured.
    */
  def openCapture(loggerName: Option[String]): Capture = synchronized {
    val (router, release) = acquire()
    val c                 = Capture(Thread.currentThread(), loggerName, router, release)
    captures = c :: captures
    c
  }

  /**
    * Hand `record` to the captures that own it. Returns false when no capture took it.
    */
  private def capture(record: jl.LogRecord): Boolean =
    val cs = captures
    if cs.isEmpty then
      false
    else
      val t = Thread.currentThread()
      // The innermost capture opened on this thread takes the record
      cs.find(c => (c.owner eq t) && c.accepts(record)) match
        case Some(c) =>
          c.add(record)
        case None if suiteThread.get() != null =>
          false
        case None =>
          cs.filter(c => (c.owner ne t) && c.accepts(record)).map(_.add(record)).contains(true)

  /**
    * Wraps the root logger's handlers. Each runner's class loader has its own copy of this class,
    * so a runner of another project in the same JVM may wrap this router in its own. After
    * `remove()`, the router passes records through and returns the handlers it wrapped from
    * `get()`, a JDK-typed accessor that a runner of any class loader can use to unwrap it.
    */
  private[spi] class RoutingHandler(original: Array[jl.Handler])
      extends jl.Handler,
        Supplier[Array[jl.Handler]]:
    @volatile
    private var removed = false

    // Number of runners and captures using this router; guarded by TestLogCapture
    private[TestLogCapture] var users = 0

    def remove(): Unit = removed = true

    override def get(): Array[jl.Handler] =
      if removed then
        original
      else
        null

    override def publish(record: jl.LogRecord): Unit =
      if removed || !capture(record) then
        deliver(record)

    /**
      * Send a record to the suite's sbt loggers, or to the original handlers
      */
    def deliver(record: jl.LogRecord): Unit =
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
    * Install the routing handler on the root logger. Returns an action that removes it again. A
    * routing handler that is already installed (e.g., by an enclosing runner or an open capture) is
    * shared, and removed when its last user releases it.
    */
  def install(): () => Unit = synchronized(acquire()._2)

  private def acquire(): (RoutingHandler, () => Unit) =
    val root     = Logger.rootLogger
    val handlers = unwrapRemoved(root.getHandlers)
    val router   =
      handlers.collectFirst { case r: RoutingHandler =>
        r
      } match
        case Some(r) =>
          setHandlers(root, handlers)
          r
        case None =>
          val r = RoutingHandler(handlers.toArray)
          setHandlers(root, Seq(r))
          r
    router.users += 1
    var released = false
    val release  =
      () =>
        synchronized {
          if !released then
            released = true
            router.users -= 1
            if router.users == 0 then
              router.remove()
              // Unwraps this router, and any router removed earlier while this one wrapped it.
              // Handlers that test code set in the meantime are kept.
              setHandlers(root, unwrapRemoved(root.getHandlers))
        }
    (router, release)

  /**
    * Route log records emitted on the current thread to `loggers` while `body` runs
    */
  def withLoggers[A](loggers: Array[sbt.testing.Logger])(body: => A): A =
    val prev = currentLoggers.get()
    currentLoggers.set(loggers)
    try body
    finally currentLoggers.set(prev)

  /**
    * Mark the current thread as running a suite while `body` runs, so its log records stay out of
    * other suites' captures
    */
  def withSuiteThread[A](body: => A): A =
    val prev = suiteThread.get()
    suiteThread.set(java.lang.Boolean.TRUE)
    try body
    finally suiteThread.set(prev)

end TestLogCapture
