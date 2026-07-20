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
  * JVM stdio transport: reads newline-delimited messages from System.in on the calling thread
  * (serve blocks until EOF) and writes responses to System.out. Writes are serialized so async
  * (`Rx`) tool results never interleave.
  */
private[mcp] object StdioTransport extends LogSupport:
  private val writeLock = new Object

  def serve(handle: String => Rx[Option[String]]): Unit =
    // Keep the real stdout for protocol responses and point System.out at stderr while serving,
    // so accidental println calls in tool code cannot corrupt the protocol stream
    val protocolOut = System.out
    System.setOut(System.err)
    try
      val reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
      var line   = reader.readLine()
      while line != null do
        val message = line.trim
        if message.nonEmpty then
          dispatchLine(handle, protocolOut, message)
        line = reader.readLine()
    finally
      System.setOut(protocolOut)

  private def dispatchLine(
      handle: String => Rx[Option[String]],
      protocolOut: java.io.PrintStream,
      line: String
  ): Unit =
    RxRunner.run(handle(line)) {
      case OnNext(v) =>
        v.asInstanceOf[Option[String]].foreach(writeLine(protocolOut, _))
      case OnError(e) =>
        // handleMessage encodes its own errors; this is defensive only
        warn(s"Failed to handle MCP message: ${e.getMessage}", e)
      case OnCompletion =>
    }

  private def writeLine(protocolOut: java.io.PrintStream, message: String): Unit = writeLock
    .synchronized {
      protocolOut.println(message)
      protocolOut.flush()
    }

end StdioTransport
