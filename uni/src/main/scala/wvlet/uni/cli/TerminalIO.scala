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
package wvlet.uni.cli

import wvlet.uni.rx.Cancelable
import wvlet.uni.rx.Rx

import scala.collection.mutable.ArrayBuffer

/**
  * Terminal size in character cells
  */
case class TerminalSize(columns: Int, rows: Int)

object TerminalSize:
  val default: TerminalSize = TerminalSize(80, 24)

/**
  * Low-level access to the process's terminal: raw mode, input bytes, output, size, and Ctrl-C.
  * [[LineReader]] is built on it, and it can be used directly for full-screen or key-driven UIs.
  *
  * Backends:
  *   - JVM: libc `termios` through the Foreign Function & Memory API (Java 22+). Older JVMs and
  *     Windows read input line by line, as when stdin is not a terminal.
  *   - Scala Native: libc `termios` through a small C shim. Windows reads line by line.
  *   - Scala.js: Node.js `process.stdin`/`process.stdout`. Not available in browsers.
  */
trait TerminalIO:
  /**
    * True if both stdin and stdout are terminals that support raw mode. When false, input is read
    * line by line and nothing should be drawn.
    */
  def isTTY: Boolean

  /**
    * The current terminal size, or [[TerminalSize.default]] when unknown
    */
  def size: TerminalSize

  /**
    * Switch the input to raw mode: no echo, no line buffering, and keys such as Ctrl-C delivered as
    * bytes instead of signals. Output post-processing is also turned off, so write `\r\n` to start
    * a new line. Does nothing when not [[isTTY]].
    */
  def enterRawMode(): Unit

  /**
    * Restore the terminal mode saved by [[enterRawMode]]. Safe to call more than once.
    */
  def exitRawMode(): Unit

  def write(s: String): Unit
  def flush(): Unit

  /**
    * Read input and pass each chunk to `handler` until it returns false. An empty chunk means the
    * end of input. The returned Rx completes when reading stops. On the JVM and Scala Native,
    * running it blocks the calling thread; on Scala.js it completes asynchronously.
    */
  def readInput(handler: Array[Byte] => Boolean): Rx[Unit]

  /**
    * Call `handler` on SIGINT (Ctrl-C outside raw mode) instead of terminating the process, until
    * the returned Cancelable is cancelled. Use it to stop a running task without exiting a REPL.
    */
  def onInterrupt(handler: () => Unit): Cancelable

end TerminalIO

object TerminalIO:
  /**
    * The terminal of the current process
    */
  def default: TerminalIO = TerminalIOCompat.default

  /**
    * A terminal that replays `input` and records the output, for testing UIs built on TerminalIO
    */
  def inMemory(
      input: Seq[Array[Byte]],
      size: TerminalSize = TerminalSize.default,
      isTTY: Boolean = true
  ): InMemoryTerminalIO = InMemoryTerminalIO(input, size, isTTY)

/**
  * A [[TerminalIO]] that replays input chunks and records what is written
  *
  * @param input
  *   chunks returned by successive reads; reading past them reports the end of input
  */
class InMemoryTerminalIO(
    input: Seq[Array[Byte]],
    var size: TerminalSize = TerminalSize.default,
    override val isTTY: Boolean = true
) extends TerminalIO:
  private val chunks      = input.iterator
  private val out         = StringBuilder()
  private val interrupts  = ArrayBuffer.empty[() => Unit]
  private var rawModeFlag = false

  def isRawMode: Boolean = rawModeFlag

  /**
    * Everything written so far, including escape sequences
    */
  def output: String = synchronized(out.toString)

  /**
    * Simulate a SIGINT
    */
  def interrupt(): Unit = interrupts.toSeq.foreach(_())

  override def enterRawMode(): Unit = rawModeFlag = isTTY
  override def exitRawMode(): Unit  = rawModeFlag = false

  override def write(s: String): Unit = synchronized(out.append(s))
  override def flush(): Unit          = ()

  override def readInput(handler: Array[Byte] => Boolean): Rx[Unit] = Rx.single {
    var continue = true
    while continue do
      val chunk =
        if chunks.hasNext then
          chunks.next()
        else
          Array.emptyByteArray
      continue = handler(chunk) && chunk.nonEmpty
  }

  override def onInterrupt(handler: () => Unit): Cancelable =
    interrupts += handler
    Cancelable(() => interrupts -= handler)

end InMemoryTerminalIO
