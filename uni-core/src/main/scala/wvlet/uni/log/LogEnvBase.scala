package wvlet.uni.log

import java.io.PrintStream

/**
  */
private[log] trait LogEnvBase:
  def initLogManager(): Unit = {
    // do nothing by default
  }

  def isScalaJS: Boolean
  def isScalaNative: Boolean

  def defaultLogLevel: LogLevel
  def defaultHandler: java.util.logging.Handler
  def defaultConsoleOutput: PrintStream

  /**
    * Whether stderr is an interactive terminal, so a person reads the console log
    */
  def isStderrTerminal: Boolean = false

  /**
    * Save a full stack trace to `dir/fileName` and return its absolute path, or None when files are
    * unavailable (Scala.js) or the write failed
    */
  def saveStackTrace(dir: String, fileName: String, content: String): Option[String] = None

  /**
    * @param cl
    * @return
    */
  def getLoggerName(cl: Class[?]): String

end LogEnvBase
