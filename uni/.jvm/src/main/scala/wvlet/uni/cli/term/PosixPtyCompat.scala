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

import java.io.IOException
private[term] object PosixPtyCompat:
  private enum Os:
    case Linux,
      MacOS

  private val os: Option[Os] =
    val name = System.getProperty("os.name", "").toLowerCase
    if name.contains("linux") then
      Some(Os.Linux)
    else if name.contains("mac") || name.contains("darwin") then
      Some(Os.MacOS)
    else
      None

  // Loading JvmPtyOps needs java.lang.foreign, so check the version first
  private lazy val loaded: Either[String, PosixPtyOps] =
    if os.isEmpty then
      Left(s"PosixPty supports Linux and macOS only, not ${System.getProperty("os.name")}")
    else if Runtime.version().feature() < 22 then
      Left(
        s"PosixPty on the JVM needs Java 22 or later (the Foreign Function & Memory API), not Java ${Runtime
            .version()
            .feature()}"
      )
    else
      try
        Right(JvmPtyOps(os.contains(Os.MacOS)))
      catch
        case e: Throwable =>
          Left(s"PosixPty cannot call libc: ${e}")

  def unsupportedReason: Option[String] = loaded.left.toOption

  def ops: PosixPtyOps = loaded.fold(
    reason => throw UnsupportedOperationException(reason),
    identity
  )

end PosixPtyCompat

/**
  * PTY calls to libc through the Foreign Function & Memory API (Java 22+). The same steps as
  * `uni_pty_shim.c` on Scala Native: `posix_openpt`, then `posix_spawnp` with `POSIX_SPAWN_SETSID`
  * and the slave opened as fd 0, which makes it the child's controlling terminal. A JVM must not
  * `fork()`, since the child would run Java code in a copy of a multithreaded process.
  *
  * The opaque `posix_spawnattr_t`, `posix_spawn_file_actions_t`, and `sigset_t` are only allocated
  * (large enough for both OSes) and passed to their libc initializers, never read field by field.
  */
private[term] class JvmPtyOps(isMac: Boolean) extends PosixPtyOps:
  import java.lang.foreign.*
  import java.lang.foreign.ValueLayout.*
  import java.lang.invoke.MethodHandle

  private val linker = Linker.nativeLinker()
  private val libc   = linker.defaultLookup()

  private val errnoState  = Linker.Option.captureCallState("errno")
  private val stateLayout = Linker.Option.captureStateLayout()
  private val errnoHandle = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))

  private def fn(
      name: String,
      descriptor: FunctionDescriptor,
      options: Linker.Option*
  ): MethodHandle = linker.downcallHandle(libc.find(name).orElseThrow(), descriptor, options*)

  // Linux and macOS share these values
  private final val O_RDWR     = 2
  private final val F_SETFD    = 2
  private final val FD_CLOEXEC = 1
  private final val F_GETFL    = 3
  private final val F_SETFL    = 4
  private final val POLLOUT    = 4
  private final val EINTR      = 4
  private final val EAGAIN     =
    if isMac then
      35
    else
      11

  private final val POLLIN      = 1
  private final val ScratchSize = 8192
  private final val O_NONBLOCK  =
    if isMac then
      0x4
    else
      0x800

  private final val O_NOCTTY =
    if isMac then
      0x20000
    else
      0x100

  private final val O_CLOEXEC =
    if isMac then
      0x1000000
    else
      0x80000

  private val POSIX_SPAWN_SETSIGDEF  = 0x04
  private val POSIX_SPAWN_SETSIGMASK = 0x08
  private val POSIX_SPAWN_SETSID     =
    if isMac then
      0x0400
    else
      0x80

  private val TIOCSWINSZ =
    if isMac then
      0x80087467L
    else
      0x5414L

  // _IOR('t', 83, char[128])
  private val TIOCPTYGNAME = 0x40807453L

  // Larger than posix_spawnattr_t (336 bytes on glibc, a pointer on macOS), posix_spawn_file_actions_t
  // (80 bytes, a pointer), and sigset_t (128 bytes, 4 bytes)
  private final val OpaqueSize = 1024

  private val posixOpenpt = fn(
    "posix_openpt",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT),
    errnoState
  )

  private val grantpt  = fn("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT), errnoState)
  private val unlockpt = fn("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT), errnoState)
  private val ptsnameR =
    if isMac then
      None
    else
      Some(fn("ptsname_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG)))

  private val fcntl = fn(
    "fcntl",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
    Linker.Option.firstVariadicArg(2)
  )

  private val ioctl = fn(
    "ioctl",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
    errnoState,
    Linker.Option.firstVariadicArg(2)
  )

  private val sigemptyset = fn("sigemptyset", FunctionDescriptor.of(JAVA_INT, ADDRESS))
  private val sigfillset  = fn("sigfillset", FunctionDescriptor.of(JAVA_INT, ADDRESS))

  private val attrInit     = fn("posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
  private val attrDestroy  = fn("posix_spawnattr_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))
  private val attrSetflags = fn(
    "posix_spawnattr_setflags",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT)
  )

  private val attrSetsigmask = fn(
    "posix_spawnattr_setsigmask",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
  )

  private val attrSetsigdefault = fn(
    "posix_spawnattr_setsigdefault",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
  )

  private val actionsInit = fn(
    "posix_spawn_file_actions_init",
    FunctionDescriptor.of(JAVA_INT, ADDRESS)
  )

  private val actionsDestroy = fn(
    "posix_spawn_file_actions_destroy",
    FunctionDescriptor.of(JAVA_INT, ADDRESS)
  )

  // mode_t is 16 bits on macOS and 32 bits on Linux
  private val actionsAddopen = fn(
    "posix_spawn_file_actions_addopen",
    FunctionDescriptor.of(
      JAVA_INT,
      ADDRESS,
      JAVA_INT,
      ADDRESS,
      JAVA_INT,
      if isMac then
        JAVA_SHORT
      else
        JAVA_INT
    )
  )

  private val actionsAdddup2 = fn(
    "posix_spawn_file_actions_adddup2",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
  )

  // glibc 2.29+ and macOS 10.15+
  private val actionsAddchdir: Option[MethodHandle] = libc
    .find("posix_spawn_file_actions_addchdir_np")
    .map(linker.downcallHandle(_, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)))
    .map(Option(_))
    .orElse(None)

  private val posixSpawnp = fn(
    "posix_spawnp",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )

  private val strerror = fn("strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT))

  // nfds_t is unsigned long on Linux and unsigned int on macOS
  private val poll = fn(
    "poll",
    FunctionDescriptor.of(
      JAVA_INT,
      ADDRESS,
      if isMac then
        JAVA_INT
      else
        JAVA_LONG
      ,
      JAVA_INT
    ),
    errnoState
  )

  private val readFn = fn(
    "read",
    FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG),
    errnoState
  )

  private val writeFn = fn(
    "write",
    FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG),
    errnoState
  )

  private val waitpid = fn(
    "waitpid",
    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
    errnoState
  )

  // open() is variadic, but its mode argument is only read with O_CREAT
  private val openFn  = fn("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), errnoState)
  private val killFn  = fn("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
  private val closeFn = fn("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))

  private def int(result: AnyRef): Int   = result.asInstanceOf[Integer].intValue
  private def long(result: AnyRef): Long = result.asInstanceOf[java.lang.Long].longValue

  private def errno(state: MemorySegment): Int = errnoHandle.get(state, 0L).asInstanceOf[Int]

  private def message(err: Int): String =
    val p = strerror.invokeWithArguments(Int.box(err)).asInstanceOf[MemorySegment]
    p.reinterpret(Long.MaxValue).getString(0)

  private def fail(what: String, err: Int): Nothing = throw IOException(s"${what}: ${message(err)}")

  override def spawn(
      file: String,
      argv: Seq[String],
      env: Seq[String],
      cwd: Option[String],
      cols: Int,
      rows: Int
  ): (Int, Int) =
    val arena = Arena.ofConfined()
    try
      val state  = arena.allocate(stateLayout)
      val master = int(posixOpenpt.invokeWithArguments(state, Int.box(O_RDWR | O_NOCTTY)))
      if master < 0 then
        fail("Failed to open a PTY", errno(state))
      var started = false
      var slaveFd = -1
      try
        // Programs started by other threads must not inherit the master, or the PTY never closes
        fcntl.invokeWithArguments(Int.box(master), Int.box(F_SETFD), Int.box(FD_CLOEXEC))
        // Non-blocking, so a writer thread waiting on a program that does not read can stop
        val flags = int(fcntl.invokeWithArguments(Int.box(master), Int.box(F_GETFL), Int.box(0)))
        fcntl.invokeWithArguments(Int.box(master), Int.box(F_SETFL), Int.box(flags | O_NONBLOCK))
        if int(grantpt.invokeWithArguments(state, Int.box(master))) != 0 ||
          int(unlockpt.invokeWithArguments(state, Int.box(master))) != 0
        then
          fail("Failed to open a PTY", errno(state))

        // ptsname() is not thread-safe, and macOS added ptsname_r only in 13.4
        val slave = arena.allocate(128)
        val named =
          ptsnameR match
            case Some(f) =>
              int(f.invokeWithArguments(Int.box(master), slave, Long.box(128L))) == 0
            case None =>
              int(
                ioctl.invokeWithArguments(state, Int.box(master), Long.box(TIOCPTYGNAME), slave)
              ) == 0
        if !named then
          throw IOException("Failed to find the PTY's device name")

        // Set the size through the slave, held open until the child has its own: macOS resets a
        // size set on the master when the slave is first opened
        slaveFd = int(
          openFn.invokeWithArguments(state, slave, Int.box(O_RDWR | O_NOCTTY | O_CLOEXEC))
        )
        if slaveFd < 0 then
          fail("Failed to open the PTY's terminal side", errno(state))
        setSize(arena, slaveFd, cols, rows)

        val none = arena.allocate(OpaqueSize)
        val all  = arena.allocate(OpaqueSize)
        sigemptyset.invokeWithArguments(none)
        sigfillset.invokeWithArguments(all)

        val attr = arena.allocate(OpaqueSize)
        attrInit.invokeWithArguments(attr)
        val actions = arena.allocate(OpaqueSize)
        actionsInit.invokeWithArguments(actions)
        try
          attrSetsigmask.invokeWithArguments(attr, none)
          attrSetsigdefault.invokeWithArguments(attr, all)
          attrSetflags.invokeWithArguments(
            attr,
            Short.box((POSIX_SPAWN_SETSID | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF).toShort)
          )
          val mode: AnyRef =
            if isMac then
              Short.box(0.toShort)
            else
              Int.box(0)
          actionsAddopen.invokeWithArguments(actions, Int.box(0), slave, Int.box(O_RDWR), mode)
          actionsAdddup2.invokeWithArguments(actions, Int.box(0), Int.box(1))
          actionsAdddup2.invokeWithArguments(actions, Int.box(0), Int.box(2))
          cwd.foreach { dir =>
            actionsAddchdir match
              case Some(f) =>
                f.invokeWithArguments(actions, arena.allocateFrom(dir))
              case None =>
                throw IOException(
                  "This C library cannot set the working directory of a spawned program (glibc 2.29+ can)"
                )
          }

          val pid = arena.allocate(JAVA_INT)
          val err = int(
            posixSpawnp.invokeWithArguments(
              pid,
              arena.allocateFrom(file),
              actions,
              attr,
              strings(arena, argv),
              strings(arena, env)
            )
          )
          if err != 0 then
            fail(s"Failed to start ${file} in a PTY", err)
          started = true
          (pid.get(JAVA_INT, 0), master)
        finally
          actionsDestroy.invokeWithArguments(actions)
          attrDestroy.invokeWithArguments(attr)
        end try
      finally
        if slaveFd >= 0 then
          close(slaveFd)
        if !started then
          close(master)
      end try
    finally
      arena.close()
    end try

  end spawn

  private def strings(arena: Arena, values: Seq[String]): MemorySegment =
    val arr = arena.allocate(ADDRESS, values.size + 1L)
    values
      .zipWithIndex
      .foreach { (v, i) =>
        arr.setAtIndex(ADDRESS, i.toLong, arena.allocateFrom(v))
      }
    arr.setAtIndex(ADDRESS, values.size.toLong, MemorySegment.NULL)
    arr

  private def setSize(arena: Arena, fd: Int, cols: Int, rows: Int): Unit =
    // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
    val ws = arena.allocate(8)
    ws.set(JAVA_SHORT, 0, rows.toShort)
    ws.set(JAVA_SHORT, 2, cols.toShort)
    val state = arena.allocate(stateLayout)
    ioctl.invokeWithArguments(state, Int.box(fd), Long.box(TIOCSWINSZ), ws)

  /**
    * Native memory for one reader or writer thread, reused across calls
    */
  private class Scratch:
    private val arena = Arena.ofAuto()
    val state         = arena.allocate(stateLayout)
    // struct pollfd { int fd; short events; short revents; }
    val pollFd = arena.allocate(8)
    val buffer = arena.allocate(ScratchSize)

  private val scratch = ThreadLocal.withInitial(() => Scratch())

  private val oneFd: AnyRef =
    if isMac then
      Int.box(1)
    else
      Long.box(1L)

  /**
    * Wait up to `timeoutMillis` for `events`. Returns 1 when ready, 0 on timeout, or -1.
    */
  private def await(s: Scratch, fd: Int, events: Int, timeoutMillis: Int): Int =
    s.pollFd.set(JAVA_INT, 0, fd)
    s.pollFd.set(JAVA_SHORT, 4, events.toShort)
    s.pollFd.set(JAVA_SHORT, 6, 0.toShort)
    val ready = int(poll.invokeWithArguments(s.state, s.pollFd, oneFd, Int.box(timeoutMillis)))
    if ready == 0 || (ready < 0 && errno(s.state) == EINTR) then
      0
    else if ready < 0 then
      -1
    else
      1

  override def read(fd: Int, buf: Array[Byte], timeoutMillis: Int): Int =
    val s     = scratch.get()
    val ready = await(s, fd, POLLIN, timeoutMillis)
    if ready <= 0 then
      ready
    else
      val len = math.min(buf.length, ScratchSize).toLong
      val n   = long(readFn.invokeWithArguments(s.state, Int.box(fd), s.buffer, Long.box(len)))
      if n > 0 then
        MemorySegment.copy(s.buffer, JAVA_BYTE, 0, buf, 0, n.toInt)
        n.toInt
      else if n < 0 && (errno(s.state) == EAGAIN || errno(s.state) == EINTR) then
        0
      else
        // Linux reports EIO once every slave fd is closed; macOS reports end of file
        -1

  override def write(fd: Int, buf: Array[Byte], offset: Int, len: Int, timeoutMillis: Int): Int =
    val s     = scratch.get()
    val ready = await(s, fd, POLLOUT, timeoutMillis)
    if ready <= 0 then
      ready
    else
      val chunk = math.min(len, ScratchSize)
      MemorySegment.copy(buf, offset, s.buffer, JAVA_BYTE, 0, chunk)
      val n = long(
        writeFn.invokeWithArguments(s.state, Int.box(fd), s.buffer, Long.box(chunk.toLong))
      )
      if n >= 0 then
        n.toInt
      else if errno(s.state) == EAGAIN || errno(s.state) == EINTR then
        0
      else
        -1

  override def resize(fd: Int, cols: Int, rows: Int): Unit =
    val arena = Arena.ofConfined()
    try setSize(arena, fd, cols, rows)
    finally arena.close()

  override def waitFor(pid: Int): Int =
    val arena = Arena.ofConfined()
    try
      val state  = arena.allocate(stateLayout)
      val status = arena.allocate(JAVA_INT)
      var r      = -1
      var retry  = true
      while retry do
        r = int(waitpid.invokeWithArguments(state, Int.box(pid), status, Int.box(0)))
        retry = r < 0 && errno(state) == EINTR
      if r < 0 then
        -1
      else
        PosixPtyOps.exitCode(status.get(JAVA_INT, 0))
    finally
      arena.close()

  override def signal(pid: Int, sig: Int): Unit = killFn.invokeWithArguments(
    Int.box(pid),
    Int.box(sig)
  )

  override def close(fd: Int): Unit = closeFn.invokeWithArguments(Int.box(fd))

end JvmPtyOps
