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

import java.util.concurrent.CopyOnWriteArrayList
import scala.scalanative.unsafe.*

/**
  * Bindings to `uni_term_shim.c`
  */
@extern
private[cli] object TermShim:
  def uni_term_isatty(fd: CInt): CInt                                 = extern
  def uni_term_enter_raw(fd: CInt): CInt                              = extern
  def uni_term_restore(fd: CInt): CInt                                = extern
  def uni_term_size(fd: CInt, cols: Ptr[CInt], rows: Ptr[CInt]): CInt = extern

  /**
    * `@blocking` parks this thread at a GC safepoint while it waits for input, so that a collection
    * started by another thread does not wait on it
    */
  @blocking
  def uni_term_read(fd: CInt, buf: Ptr[Byte], len: CInt): CInt = extern

  def uni_term_sigint_install(): CInt = extern
  def uni_term_sigint_reset(): CInt   = extern

  @blocking
  def uni_term_sigint_wait(): CInt = extern

end TermShim

private[cli] object TerminalIOCompat:
  lazy val default: TerminalIO = NativeTerminalIO()

/**
  * The process's terminal on Scala Native, through libc `termios`. On Windows, input is read line
  * by line.
  */
private[cli] class NativeTerminalIO extends TerminalIO:
  private final val ChunkSize = 4096

  override val isTTY: Boolean = TermShim.uni_term_isatty(0) == 1 && TermShim.uni_term_isatty(1) == 1

  private var rawMode          = false
  private var restoreOnExit    = false
  private val interruptHooks   = CopyOnWriteArrayList[() => Unit]()
  private var interruptWatcher = Option.empty[Thread]

  override def size: TerminalSize =
    val cols = stackalloc[CInt]()
    val rows = stackalloc[CInt]()
    if TermShim.uni_term_size(1, cols, rows) == 0 then
      TerminalSize(!cols, !rows)
    else
      TerminalSize(CliEnv.terminalWidth, CliEnv.terminalHeight)

  override def enterRawMode(): Unit = synchronized {
    if isTTY && !rawMode && TermShim.uni_term_enter_raw(0) == 0 then
      rawMode = true
      if !restoreOnExit then
        restoreOnExit = true
        Runtime.getRuntime.addShutdownHook(Thread(() => exitRawMode()))
  }

  override def exitRawMode(): Unit = synchronized {
    if rawMode then
      TermShim.uni_term_restore(0)
      rawMode = false
  }

  override def write(s: String): Unit = System.out.print(s)
  override def flush(): Unit          = System.out.flush()

  override def readInput(handler: Array[Byte] => Boolean): Rx[Unit] = Rx.single {
    val buf      = stackalloc[Byte](ChunkSize)
    var continue = true
    while continue do
      val n = TermShim.uni_term_read(0, buf, ChunkSize)
      if n <= 0 then
        handler(Array.emptyByteArray)
        continue = false
      else
        val chunk = Array.ofDim[Byte](n)
        var i     = 0
        while i < n do
          chunk(i) = buf(i)
          i += 1
        continue = handler(chunk)
  }

  override def onInterrupt(handler: () => Unit): Cancelable = synchronized {
    interruptHooks.add(handler)
    if interruptHooks.size == 1 && TermShim.uni_term_sigint_install() == 0 then
      startInterruptWatcher()
    Cancelable { () =>
      synchronized {
        interruptHooks.remove(handler)
        if interruptHooks.isEmpty then
          TermShim.uni_term_sigint_reset()
      }
    }
  }

  // One daemon thread for the process's lifetime; it sleeps in the shim while no signal arrives
  private def startInterruptWatcher(): Unit =
    if interruptWatcher.isEmpty then
      val t = Thread(() =>
        while TermShim.uni_term_sigint_wait() == 0 do
          interruptHooks.forEach(h => h())
      )
      t.setName("uni-sigint")
      t.setDaemon(true)
      t.start()
      interruptWatcher = Some(t)

end NativeTerminalIO
