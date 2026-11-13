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
package wvlet.uni.cli.term

import wvlet.uni.log.LogSupport

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/**
  * Starts programs in a pseudo-terminal on Linux and macOS, from the JVM (Java 22 or later) and
  * Scala Native.
  *
  * The program runs in its own session with the PTY as its controlling terminal, so `isatty`,
  * `stty size`, job control, Ctrl-C, and SIGWINCH on [[PtyProcess.resize]] behave as in a real
  * terminal. On the JVM, libc is called through the Foreign Function & Memory API; Java 24 and
  * later print a warning on the first native call unless the JVM runs with
  * `--enable-native-access=ALL-UNNAMED`.
  *
  * On Windows and on JVMs before Java 22, [[spawn]] throws `UnsupportedOperationException`; check
  * [[isSupported]] first. On Node.js and Electron (Scala.js), use `NodePty` instead.
  * {{{
  *   val terminal = TerminalEmulator(80, 24)
  *   val shell    = PosixPty.spawn("/bin/bash", Seq("-l"), PtyOptions().withSize(80, 24))
  *   PtyProcess.connect(terminal, shell)
  *   shell.onExit(code => println(s"exited with ${code}"))
  * }}}
  */
object PosixPty:
  /**
    * Why PTYs are unavailable on this platform, or None when [[spawn]] works
    */
  def unsupportedReason: Option[String] = PosixPtyCompat.unsupportedReason

  def isSupported: Boolean = unsupportedReason.isEmpty

  /**
    * Start `file` with `args` in a new pseudo-terminal. `file` is looked up in `PATH` unless it
    * contains a `/`. The environment is the current process's, plus `options.env` and `TERM`.
    *
    * The exit code passed to [[PtyProcess.onExit]] is the program's exit status, or 128 plus the
    * signal number when a signal ended it, as shells report it. [[PtyProcess.kill]] sends SIGHUP,
    * as closing a terminal window does.
    *
    * @throws UnsupportedOperationException
    *   if this platform has no PTY support (see [[unsupportedReason]])
    * @throws java.io.IOException
    *   if the PTY cannot be opened or the program cannot be started
    */
  def spawn(
      file: String,
      args: Seq[String] = Seq.empty,
      options: PtyOptions = PtyOptions()
  ): PtyProcess =
    unsupportedReason.foreach(reason => throw UnsupportedOperationException(reason))
    val env       = (sys.env ++ options.env) + ("TERM" -> options.termName)
    val ops       = PosixPtyCompat.ops
    val cols      = math.max(1, options.cols)
    val rows      = math.max(1, options.rows)
    val (pid, fd) = ops.spawn(
      file,
      file +: args,
      env.map((k, v) => s"${k}=${v}").toSeq,
      options.cwd,
      cols,
      rows
    )
    PosixPtyProcess(ops, pid, fd, file)

end PosixPty

/**
  * The libc calls a PTY needs, implemented through FFM on the JVM and a C shim on Scala Native
  */
private[term] trait PosixPtyOps:
  /**
    * Open a PTY of the given size and start `file` in it. Returns (pid, master fd).
    */
  def spawn(
      file: String,
      argv: Seq[String],
      env: Seq[String],
      cwd: Option[String],
      cols: Int,
      rows: Int
  ): (Int, Int)

  /**
    * Wait up to `timeoutMillis` for output. Returns the number of bytes read, 0 on timeout, or -1
    * at the end of output (all slave ends closed)
    */
  def read(fd: Int, buf: Array[Byte], timeoutMillis: Int): Int

  /**
    * Write all `len` bytes. Returns false if the PTY is gone.
    */
  def write(fd: Int, buf: Array[Byte], len: Int): Boolean

  def resize(fd: Int, cols: Int, rows: Int): Unit

  /**
    * Wait for `pid` to exit and reap it. Returns the exit status, or 128 + signal number.
    */
  def waitFor(pid: Int): Int

  def signal(pid: Int, sig: Int): Unit
  def close(fd: Int): Unit

end PosixPtyOps

private[term] object PosixPtyOps:
  // The same numbers on Linux and macOS
  final val SIGHUP = 1

  /**
    * Decode a `waitpid` status (the W* macros agree on Linux and macOS)
    */
  def exitCode(status: Int): Int =
    if (status & 0x7f) == 0 then
      (status >> 8) & 0xff
    else
      128 + (status & 0x7f)

/**
  * A running PTY program. A reader thread delivers output, and a waiter thread reaps the program,
  * lets the reader drain the remaining output, closes the PTY, and then reports the exit.
  */
private[term] class PosixPtyProcess(ops: PosixPtyOps, pid: Int, fd: Int, name: String)
    extends PtyProcess
    with LogSupport:
  import PosixPtyProcess.*

  private val ioLock = Object()
  // Guarded by ioLock: true once the master fd is closed, so its number is never reused by mistake
  private var closed = false
  // Set once the program has been reaped, so kill() never signals a recycled pid
  @volatile
  private var reaped = false

  private val dataLock     = Object()
  private val dataPending  = ArrayBuffer.empty[String]
  private val dataHandlers = CopyOnWriteArrayList[String => Unit]()

  private val exitLock     = Object()
  private var exitStatus   = Option.empty[Int]
  private val exitHandlers = ArrayBuffer.empty[Int => Unit]

  private val readerDone = CountDownLatch(1)
  @volatile
  private var stopReading = false

  private val reader = Thread(() => readLoop(), s"uni-pty-reader-${pid}")
  reader.setDaemon(true)
  reader.start()

  private val waiter = Thread(() => waitLoop(), s"uni-pty-waiter-${pid}")
  waiter.setDaemon(true)
  waiter.start()

  private def readLoop(): Unit =
    val buf     = Array.ofDim[Byte](ReadBufferSize)
    val decoder = Utf8StreamDecoder()
    try
      var continue = true
      while continue && !stopReading do
        val n = ops.read(fd, buf, PollMillis)
        if n > 0 then
          emit(decoder.decode(buf, n))
        else if n < 0 then
          continue = false
      emit(decoder.flush())
    catch
      case e: Throwable =>
        warn(s"Reading from the PTY of ${name} (pid ${pid}) failed", e)
    finally
      readerDone.countDown()

  private def waitLoop(): Unit =
    val code =
      try
        ops.waitFor(pid)
      catch
        case e: Throwable =>
          warn(s"Waiting for ${name} (pid ${pid}) failed", e)
          -1
    reaped = true
    // Output written just before the exit may still be in the PTY. A background job that keeps the
    // terminal open would hold the reader forever, so the drain is bounded.
    if !readerDone.await(DrainMillis, TimeUnit.MILLISECONDS) then
      stopReading = true
      readerDone.await()
    ioLock.synchronized {
      closed = true
      ops.close(fd)
    }
    val handlers = exitLock.synchronized {
      exitStatus = Some(code)
      exitHandlers.toList
    }
    handlers.foreach(h => runHandler(h(code)))

  private def emit(data: String): Unit =
    if data.nonEmpty then
      dataLock.synchronized {
        if dataHandlers.isEmpty then
          dataPending += data
        else
          dataHandlers.forEach(h => runHandler(h(data)))
      }

  private def runHandler(body: => Unit): Unit =
    try
      body
    catch
      case e: Throwable =>
        warn(s"A PTY listener for ${name} failed", e)

  override def write(data: String): Unit =
    val bytes = data.getBytes(StandardCharsets.UTF_8)
    ioLock.synchronized {
      if !closed then
        ops.write(fd, bytes, bytes.length)
    }

  override def resize(cols: Int, rows: Int): Unit = ioLock.synchronized {
    if !closed then
      ops.resize(fd, math.max(1, cols), math.max(1, rows))
  }

  override def onData(listener: String => Unit): Unit = dataLock.synchronized {
    dataHandlers.add(listener)
    // Output that arrived before the first listener
    dataPending.foreach(d => runHandler(listener(d)))
    dataPending.clear()
  }

  override def onExit(listener: Int => Unit): Unit =
    val done = exitLock.synchronized {
      if exitStatus.isEmpty then
        exitHandlers += listener
      exitStatus
    }
    done.foreach(code => runHandler(listener(code)))

  override def kill(): Unit =
    if !reaped then
      ops.signal(pid, PosixPtyOps.SIGHUP)

  override def toString: String = s"PosixPtyProcess(${name}, pid=${pid})"

end PosixPtyProcess

private[term] object PosixPtyProcess:
  private final val ReadBufferSize = 8192
  private final val PollMillis     = 100
  private final val DrainMillis    = 500L

/**
  * Decodes a UTF-8 byte stream chunk by chunk. A character whose bytes are split across chunks is
  * held back until the rest arrives, instead of becoming two replacement characters.
  */
private[term] class Utf8StreamDecoder:
  private var carry: Array[Byte] = Array.emptyByteArray

  def decode(bytes: Array[Byte], length: Int): String =
    val input =
      if carry.isEmpty then
        bytes
      else
        val joined = Array.ofDim[Byte](carry.length + length)
        System.arraycopy(carry, 0, joined, 0, carry.length)
        System.arraycopy(bytes, 0, joined, carry.length, length)
        joined
    val total    = carry.length + length
    val complete = total - incompleteTail(input, total)
    carry = java.util.Arrays.copyOfRange(input, complete, total)
    String(input, 0, complete, StandardCharsets.UTF_8)

  /**
    * Decode what is left, e.g., when the stream ends inside a character
    */
  def flush(): String =
    val rest = String(carry, StandardCharsets.UTF_8)
    carry = Array.emptyByteArray
    rest

  /**
    * The number of bytes at the end of `bytes(0 until length)` that start a character but do not
    * finish it
    */
  private def incompleteTail(bytes: Array[Byte], length: Int): Int =
    var i     = length - 1
    var count = 0
    // Step back over continuation bytes (10xxxxxx), at most 3
    while i >= 0 && count < 3 && (bytes(i) & 0xc0) == 0x80 do
      i -= 1
      count += 1
    if i < 0 then
      0
    else
      val lead = bytes(i) & 0xff
      val need =
        if lead >= 0xf0 && lead <= 0xf4 then
          4
        else if lead >= 0xe0 && lead <= 0xef then
          3
        else if lead >= 0xc2 && lead <= 0xdf then
          2
        else
          1
      val have = count + 1
      if need > have then
        have
      else
        0

end Utf8StreamDecoder
