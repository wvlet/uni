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

import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Cancelable
import wvlet.uni.rx.Rx

import java.util.concurrent.CopyOnWriteArrayList

private[cli] object TerminalIOCompat:
  lazy val default: TerminalIO = JvmTerminalIO()

/**
  * The process's terminal on the JVM. Raw mode and the window size go through a [[NativeTerminal]]:
  * libc through the Foreign Function & Memory API on Java 22 and later, or the `stty` command on
  * older JVMs (and when FFM fails to load). On Windows, or when neither works, input is read line
  * by line.
  *
  * Java 24 and later print a warning on the first native call unless the JVM runs with
  * `--enable-native-access=ALL-UNNAMED` (or the module name).
  */
private[cli] class JvmTerminalIO extends TerminalIO with LogSupport:
  private val native: Option[NativeTerminal] = NativeTerminal.load(
    Runtime.version().feature(),
    CliEnv.isWindows
  )

  override val isTTY: Boolean = native.exists(_.isTTY)

  private var rawMode         = false
  private var restoreOnExit   = false
  private val interruptHooks  = CopyOnWriteArrayList[() => Unit]()
  private var previousHandler = Option.empty[sun.misc.SignalHandler]

  override def size: TerminalSize = native
    .flatMap(_.windowSize)
    .getOrElse(TerminalSize(CliEnv.terminalWidth, CliEnv.terminalHeight))

  override def enterRawMode(): Unit = synchronized {
    if isTTY && !rawMode && native.exists(_.enterRawMode()) then
      rawMode = true
      if !restoreOnExit then
        restoreOnExit = true
        Runtime.getRuntime.addShutdownHook(Thread(() => exitRawMode()))
  }

  override def exitRawMode(): Unit = synchronized {
    if rawMode then
      native.foreach(_.restore())
      rawMode = false
  }

  override def write(s: String): Unit = System.out.print(s)
  override def flush(): Unit          = System.out.flush()

  override def readInput(handler: Array[Byte] => Boolean): Rx[Unit] = Rx.single {
    val buf      = Array.ofDim[Byte](4096)
    var continue = true
    while continue do
      val n = System.in.read(buf)
      if n < 0 then
        handler(Array.emptyByteArray)
        continue = false
      else if n > 0 then
        continue = handler(buf.take(n))
  }

  override def onInterrupt(handler: () => Unit): Cancelable = synchronized {
    interruptHooks.add(handler)
    if previousHandler.isEmpty then
      previousHandler = Some(
        sun.misc.Signal.handle(sun.misc.Signal("INT"), _ => interruptHooks.forEach(h => h()))
      )
    Cancelable { () =>
      synchronized {
        interruptHooks.remove(handler)
        if interruptHooks.isEmpty then
          previousHandler.foreach(h => sun.misc.Signal.handle(sun.misc.Signal("INT"), h))
          previousHandler = None
      }
    }
  }

end JvmTerminalIO

/**
  * libc terminal calls through the Foreign Function & Memory API (Java 22+). Loading this class on
  * an older JVM fails, so [[JvmTerminalIO]] checks the Java version first.
  *
  * `cfmakeraw` sets the raw-mode flags, so this code never reads `struct termios` fields, whose
  * layout and flag values differ between Linux and macOS. The struct is only copied as bytes.
  */
private[cli] class PosixTerminal extends NativeTerminal:
  import java.lang.foreign.*
  import java.lang.foreign.ValueLayout.*

  private val linker = Linker.nativeLinker()
  private val libc   = linker.defaultLookup()

  private def function(
      name: String,
      descriptor: FunctionDescriptor,
      options: Linker.Option*
  ): java.lang.invoke.MethodHandle = linker.downcallHandle(
    libc.find(name).orElseThrow(),
    descriptor,
    options*
  )

  private val isattyFn    = function("isatty", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
  private val tcgetattrFn = function(
    "tcgetattr",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)
  )

  private val tcsetattrFn = function(
    "tcsetattr",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)
  )

  private val cfmakerawFn = function("cfmakeraw", FunctionDescriptor.ofVoid(ADDRESS))
  private val ioctlFn     = function(
    "ioctl",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
    Linker.Option.firstVariadicArg(2)
  )

  // Larger than struct termios on any supported OS (60 bytes on Linux, 72 on macOS)
  private final val TermiosSize        = 256
  private final val TCSANOW            = 0
  private val TIOCGWINSZ: Option[Long] =
    val os = System.getProperty("os.name", "").toLowerCase
    if os.contains("linux") then
      Some(0x5413L)
    else if os.contains("mac") || os.contains("darwin") || os.contains("bsd") then
      Some(0x40087468L)
    else
      None

  // The mode to restore; lives as long as this object
  private val saved = Arena.ofAuto().allocate(TermiosSize)

  private def call(fn: java.lang.invoke.MethodHandle, args: AnyRef*): Int =
    fn.invokeWithArguments(args*).asInstanceOf[Integer].intValue

  def isatty(fd: Int): Boolean = call(isattyFn, Int.box(fd)) == 1

  override def isTTY: Boolean                   = isatty(0) && isatty(1)
  override def enterRawMode(): Boolean          = enterRawMode(0)
  override def restore(): Unit                  = restore(0)
  override def windowSize: Option[TerminalSize] = windowSize(1)

  /**
    * Save the current mode and switch to raw mode. Returns false if either call fails, e.g., in a
    * background process group.
    */
  def enterRawMode(fd: Int): Boolean =
    if call(tcgetattrFn, Int.box(fd), saved) != 0 then
      false
    else
      val local = Arena.ofConfined()
      try
        val raw = local.allocate(TermiosSize)
        raw.copyFrom(saved)
        cfmakerawFn.invokeWithArguments(raw)
        call(tcsetattrFn, Int.box(fd), Int.box(TCSANOW), raw) == 0
      finally
        local.close()

  def restore(fd: Int): Unit = call(tcsetattrFn, Int.box(fd), Int.box(TCSANOW), saved)

  def windowSize(fd: Int): Option[TerminalSize] = TIOCGWINSZ.flatMap { request =>
    val local = Arena.ofConfined()
    try
      val ws = local.allocate(8)
      if call(ioctlFn, Int.box(fd), Long.box(request), ws) == 0 then
        // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
        val rows = ws.get(JAVA_SHORT, 0) & 0xffff
        val cols = ws.get(JAVA_SHORT, 2) & 0xffff
        if cols > 0 && rows > 0 then
          Some(TerminalSize(cols, rows))
        else
          None
      else
        None
    finally
      local.close()
  }

end PosixTerminal
