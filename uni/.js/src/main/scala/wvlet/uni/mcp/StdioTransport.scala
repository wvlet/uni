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
package wvlet.uni.mcp

import wvlet.uni.log.{ConsoleLogHandler, LogSupport, Logger}
import wvlet.uni.log.LogFormatter.SourceCodeLogFormatter
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

/**
  * Node.js stdio transport: event-loop driven (never blocks). Reads newline-delimited messages via
  * `process.stdin` data events and writes responses with `process.stdout.write`. `serve` returns
  * immediately; the process stays alive while stdin is open.
  */
private[mcp] object StdioTransport extends LogSupport:

  def serve(handle: String => Rx[Option[String]]): Unit =
    // The default JS log handler writes to console.log (stdout on Node), which would corrupt the
    // protocol stream: route all logs to stderr instead.
    Logger.setDefaultHandler(new ConsoleLogHandler(SourceCodeLogFormatter, Console.err))
    val stdin = g.process.stdin
    stdin.setEncoding("utf8")
    var buffer                             = ""
    val onData: js.Function1[String, Unit] =
      (chunk: String) =>
        buffer += chunk
        var newlineIndex = buffer.indexOf("\n")
        while newlineIndex >= 0 do
          val line = buffer.substring(0, newlineIndex).trim
          buffer = buffer.substring(newlineIndex + 1)
          if line.nonEmpty then
            dispatchLine(handle, line)
          newlineIndex = buffer.indexOf("\n")
    stdin.on("data", onData)

  private def dispatchLine(handle: String => Rx[Option[String]], line: String): Unit =
    RxRunner.run(handle(line)) {
      case OnNext(v) =>
        v.asInstanceOf[Option[String]].foreach(writeLine)
      case OnError(e) =>
        // handleMessage encodes its own errors; this is defensive only
        warn(s"Failed to handle MCP message: ${e.getMessage}", e)
      case OnCompletion =>
    }

  private def writeLine(message: String): Unit = g.process.stdout.write(message + "\n")

end StdioTransport
