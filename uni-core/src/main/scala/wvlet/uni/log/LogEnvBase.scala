package wvlet.uni.log

import java.io.PrintStream

/**
  */
private[log] trait LogEnvBase:
  def initLogManager(): Unit = {
    // do nothing by default
  }

  /**
    * Whether the root logger's handlers belong to another copy of Uni that still uses them, so
    * initializing this copy's root logger must keep them. Only the JVM can load several copies.
    */
  def keepsRootHandlers(handlers: Array[java.util.logging.Handler]): Boolean = false

  def isScalaJS: Boolean
  def isScalaNative: Boolean

  def defaultLogLevel: LogLevel
  def defaultHandler: java.util.logging.Handler
  def defaultConsoleOutput: PrintStream

  /**
    * @param cl
    * @return
    */
  def getLoggerName(cl: Class[?]): String

end LogEnvBase
