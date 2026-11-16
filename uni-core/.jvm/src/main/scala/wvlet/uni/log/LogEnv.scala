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

  // System.console() needs stdin and stdout to be terminals; since JDK 22 it can be non-null for
  // redirected output, and Console.isTerminal tells them apart
  override lazy val isStderrTerminal: Boolean =
    val c = System.console()
    c != null && {
      try
        classOf[java.io.Console].getMethod("isTerminal").invoke(c) == java.lang.Boolean.TRUE
      catch
        case _: NoSuchMethodException =>
          true
        case _: Exception =>
          false
    }

  override def saveStackTrace(dir: String, fileName: String, content: String): Option[String] =
    try
      val d = java.nio.file.Paths.get(dir).toAbsolutePath
      java.nio.file.Files.createDirectories(d)
      val f = d.resolve(fileName)
      java.nio.file.Files.write(f, content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      Some(f.toString)
    catch
      case _: Exception =>
        None

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
