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
package wvlet.uni.log

import wvlet.uni.log.ConsoleLogHandler
import wvlet.uni.log.LogEnvBase
import wvlet.uni.log.LogLevel

import java.io.PrintStream
import wvlet.uni.log.LogFormatter.SourceCodeLogFormatter

private[log] object LogEnv extends LogEnvBase:
  override def isScalaJS: Boolean                        = false
  override def isScalaNative: Boolean                    = true
  override def defaultLogLevel: LogLevel                 = LogLevel.INFO
  override def defaultHandler: java.util.logging.Handler =
    new ConsoleLogHandler(SourceCodeLogFormatter)

  override def defaultConsoleOutput: PrintStream = System.err

  override lazy val isStderrTerminal: Boolean = scala.scalanative.posix.unistd.isatty(2) == 1

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

    val pos = name.indexOf("$")
    if pos > 0 then
      // Remove trailing $xxx
      name = name.substring(0, pos)
    name

end LogEnv
