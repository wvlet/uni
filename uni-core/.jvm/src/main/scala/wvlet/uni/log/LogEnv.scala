package wvlet.uni.log

import wvlet.uni.log.ConsoleLogHandler
import wvlet.uni.log.LogEnvBase
import wvlet.uni.log.LogLevel
import wvlet.uni.log.LogFormatter.SourceCodeLogFormatter

import java.io.PrintStream

/**
  */
private[log] object LogEnv extends LogEnvBase:
  override def isScalaJS: Boolean        = false
  override def isScalaNative: Boolean    = false
  override def defaultLogLevel: LogLevel = LogLevel.INFO

  override val defaultConsoleOutput: PrintStream =
    // Note: In normal circumstances, using System.err here is fine, but
    // System.err can be replaced with other implementations
    // (e.g., airlift.Logging, which is used in Trino https://github.com/airlift/airlift/blob/master/log-manager/src/main/java/io/airlift/log/Logging.java),
    // If that happens, we may need to create a stderr stream explicitly like this
    // new PrintStream(new FileOutputStream(FileDescriptor.err))

    // Use the standard System.err for sbtn native client
    System.err

  override def defaultHandler: java.util.logging.Handler =
    new ConsoleLogHandler(SourceCodeLogFormatter)

  /**
    * Keep the root handlers when they hold a live log router of another copy of Uni. sbt runs the
    * tests of several projects in one JVM, each with its own class loader and copy of Uni, and a
    * project's runner may start while another project's suites route or capture logs. A router (see
    * uni-test's TestLogCapture) implements `Supplier`, a JDK type every class loader shares, and
    * returns null from `get()` until it is removed. Other handlers of another copy are replaced as
    * usual, so a finished runner's class loader is not kept in use.
    */
  override def keepsRootHandlers(handlers: Array[java.util.logging.Handler]): Boolean = handlers
    .exists { h =>
      val cl = h.getClass
      cl.getName.startsWith("wvlet.uni.") &&
      (cl.getClassLoader ne getClass.getClassLoader) && (
        h match
          case s: java.util.function.Supplier[?] =>
            s.get() == null
          case _ =>
            false
      )
    }

  /**
    * @param cl
    * @return
    */
  override def getLoggerName(cl: Class[?]): String =
    var name = cl.getName

    if name.endsWith("$") then
      // Remove trailing $ of Scala Object name
      name = name.substring(0, name.length - 1)

    // When class is an anonymous trait
    if name.contains("$anon$") then
      val interfaces = cl.getInterfaces
      if interfaces != null && interfaces.length > 0 then
        // Use the first interface name instead of the anonymous name
        name = interfaces(0).getName
    name

end LogEnv
