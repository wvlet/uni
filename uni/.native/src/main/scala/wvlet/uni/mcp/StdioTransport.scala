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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets

/**
  * Scala Native stdio transport: reads newline-delimited messages from System.in on the calling
  * thread (serve blocks until EOF) and writes responses to System.out. Writes are serialized so
  * async (`Rx`) tool results never interleave.
  */
private[mcp] object StdioTransport extends LogSupport:
  private val writeLock = new Object

  def serve(handle: String => Rx[Option[String]]): Unit =
    val reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
    var line   = reader.readLine()
    while line != null do
      val message = line.trim
      if message.nonEmpty then
        dispatchLine(handle, message)
      line = reader.readLine()

  private def dispatchLine(handle: String => Rx[Option[String]], line: String): Unit =
    RxRunner.run(handle(line)) {
      case OnNext(v) =>
        v.asInstanceOf[Option[String]].foreach(writeLine)
      case OnError(e) =>
        // handleMessage encodes its own errors; this is defensive only
        warn(s"Failed to handle MCP message: ${e.getMessage}", e)
      case OnCompletion =>
    }

  private def writeLine(message: String): Unit = writeLock.synchronized {
    System.out.println(message)
    System.out.flush()
  }

end StdioTransport
