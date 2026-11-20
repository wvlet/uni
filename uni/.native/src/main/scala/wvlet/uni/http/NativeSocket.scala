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
package wvlet.uni.http

import scala.scalanative.libc.string as cstring
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.netinet.in.{in_addr, sockaddr_in, IPPROTO_TCP}
import scala.scalanative.posix.netinet.tcp.TCP_NODELAY
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.sys.socket as csocket
import scala.scalanative.posix.sys.socket.{sockaddr, socklen_t}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/**
  * The three socket calls that differ on Windows, from `uni_socket_shim.c`: winsock startup, the
  * readable-wait (`poll` vs `WSAPoll`), and closing a socket (`close` vs `closesocket`). Scala
  * Native's posixlib builds `poll.c` only on unix/Apple, so referencing `scalanative.posix.poll`
  * here would break every Windows Native build reaching this object. See
  * `adr/2026-07-08-native-socket-shim.md`.
  */
@extern
private[http] object SocketShim:
  def uni_socket_startup(): CInt = extern

  /**
    * `@blocking` parks this thread at a GC safepoint for the duration of the call, exactly as Scala
    * Native annotates `posix.poll.poll`. Without it the garbage collector waits on a thread sitting
    * inside `poll`, times out after 60s and aborts the process.
    */
  @blocking
  def uni_socket_wait_readable(fd: CInt, timeoutMillis: CInt): CInt = extern

  def uni_socket_close(fd: CInt): CInt = extern

  /**
    * Non-blocking `recv` (`MSG_DONTWAIT`): the count, 0 at EOF, -1 on error, or -2 when nothing is
    * waiting. Not `@blocking`, since it never waits. Always -3 on Windows, which has no
    * `MSG_DONTWAIT`: the caller can't tell whether bytes are waiting and polls.
    */
  def uni_socket_recv_ready(fd: CInt, buf: Ptr[Byte], len: CInt): CInt = extern

  /** Make a send to a closed peer fail with EPIPE instead of raising SIGPIPE (macOS). */
  def uni_socket_no_sigpipe(fd: CInt): Unit = extern

  /** Flags for every `send`: `MSG_NOSIGNAL` where it exists (Linux), else 0. */
  def uni_socket_send_flags(): CInt = extern

  /** An epoll/kqueue fd with a wake pipe in `wake(0)`/`wake(1)`, or -1 where unsupported. */
  def uni_poller_create(wake: Ptr[CInt]): CInt = extern

  /** Watch `fd` for readability once, reporting `token`. */
  def uni_poller_add(poller: CInt, fd: CInt, token: CLongLong): CInt = extern

  /** Wait for ready tokens; `@blocking` for the same reason as `uni_socket_wait_readable`. */
  @blocking
  def uni_poller_wait(poller: CInt, tokens: Ptr[CLongLong], max: CInt, timeoutMillis: CInt): CInt =
    extern

  /**
    * Grow Boehm's heap to at least `target` bytes unless GC_INITIAL_HEAP_SIZE is set (see
    * uni_gc_shim.c)
    */
  def uni_gc_expand_heap_to(target: CLongLong): CLongLong = extern

  def uni_poller_wake(wakeWriteFd: CInt): Unit = extern
  def uni_poller_drain(wakeReadFd: CInt): Unit = extern

end SocketShim

/**
  * Thin wrappers over POSIX TCP sockets (libc, via Scala Native's posixlib) for the Native HTTP
  * server. No external dependency or linker flag is required.
  */
private[http] object NativeSocket:

  private final val ChunkSize = 8192

  // A send to a closed peer must fail, not kill the process with SIGPIPE.
  private lazy val SendFlags: CInt = SocketShim.uni_socket_send_flags()

  /**
    * Initialize the socket subsystem. A no-op on unix; on Windows, winsock rejects every `socket()`
    * until `WSAStartup` has run, and Scala Native only calls it from its own `java.net` code, which
    * uni's posixlib-based sockets never reach. Idempotent, so callers need not track it.
    */
  private def ensureStarted(): Unit =
    if SocketShim.uni_socket_startup() != 0 then
      throw HttpException.connectionFailed("Failed to initialize the socket subsystem")

  /**
    * Create a listening TCP socket bound to host:port (port 0 = OS-assigned). Returns the socket fd
    * and the actually-bound port.
    */
  def bindAndListen(host: String, port: Int, backlog: Int): (Int, Int) =
    if port < 0 || port > 65535 then
      throw HttpException.connectionFailed(s"Invalid port: ${port}")
    ensureStarted()
    val fd = csocket.socket(csocket.AF_INET, csocket.SOCK_STREAM, 0)
    if fd < 0 then
      throw HttpException.connectionFailed("Failed to create socket")

    // Close the fd on any failure — setBindAddress rejecting an invalid host, or a failed
    // bind/listen/getsockname — rather than leaking it.
    try
      // SO_REUSEADDR so a restart can rebind the port immediately.
      val optval = stackalloc[CInt]()
      !optval = 1
      csocket.setsockopt(
        fd,
        csocket.SOL_SOCKET,
        csocket.SO_REUSEADDR,
        optval.asInstanceOf[Ptr[Byte]],
        sizeof[CInt].toUInt
      )

      val addr = stackalloc[sockaddr_in]()
      cstring.memset(addr.asInstanceOf[Ptr[Byte]], 0, sizeof[sockaddr_in])
      addr.sin_family = csocket.AF_INET.toUShort
      addr.sin_port = inet.htons(port.toUShort)
      setBindAddress(addr, host)

      if csocket.bind(fd, addr.asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_in].toUInt) < 0 then
        throw HttpException.connectionFailed(s"Failed to bind to ${host}:${port}")

      if csocket.listen(fd, backlog) < 0 then
        throw HttpException.connectionFailed(s"Failed to listen on ${host}:${port}")

      (fd, resolveBoundPort(fd, port))
    catch
      case e: Throwable =>
        SocketShim.uni_socket_close(fd)
        throw e

  end bindAndListen

  /**
    * Open a TCP connection to host:port (IPv4 dotted-quad or "localhost"). Returns the client fd.
    */
  def connect(host: String, port: Int): Int =
    if port < 0 || port > 65535 then
      throw HttpException.connectionFailed(s"Invalid port: ${port}")
    ensureStarted()
    val fd = csocket.socket(csocket.AF_INET, csocket.SOCK_STREAM, 0)
    if fd < 0 then
      throw HttpException.connectionFailed("Failed to create socket")
    SocketShim.uni_socket_no_sigpipe(fd)
    val addr = stackalloc[sockaddr_in]()
    cstring.memset(addr.asInstanceOf[Ptr[Byte]], 0, sizeof[sockaddr_in])
    addr.sin_family = csocket.AF_INET.toUShort
    addr.sin_port = inet.htons(port.toUShort)
    // Close the fd on any failure (e.g. setBindAddress rejecting an invalid host) — don't leak it.
    try
      setBindAddress(addr, host)
      if csocket.connect(fd, addr.asInstanceOf[Ptr[sockaddr]], sizeof[sockaddr_in].toUInt) < 0 then
        throw HttpException.connectionFailed(s"Failed to connect to ${host}:${port}")
      fd
    catch
      case e: Throwable =>
        SocketShim.uni_socket_close(fd)
        throw e

  private def setBindAddress(addr: Ptr[sockaddr_in], host: String): Unit =
    // INADDR_ANY (0) for wildcard; otherwise parse a dotted-quad. "localhost" maps to loopback.
    val normalized =
      if host == "localhost" then
        "127.0.0.1"
      else
        host
    if normalized.isEmpty || normalized == "0.0.0.0" then
      () // already zeroed = INADDR_ANY
    else
      val ia = stackalloc[in_addr]()
      Zone.acquire { implicit z =>
        // in_addr is CStruct1[in_addr_t]; its single field is s_addr.
        val parsed = inet.inet_addr(toCString(normalized))
        // inet_addr returns INADDR_NONE (0xFFFFFFFF) for an unparseable address (no DNS here).
        if parsed == 0xffffffff.toUInt then
          throw HttpException.connectionFailed(s"Invalid bind host: ${host}")
        ia._1 = parsed
      }
      addr.sin_addr = !ia

  private def resolveBoundPort(fd: Int, requestedPort: Int): Int =
    if requestedPort != 0 then
      requestedPort
    else
      val outAddr = stackalloc[sockaddr_in]()
      val outLen  = stackalloc[socklen_t]()
      !outLen = sizeof[sockaddr_in].toUInt
      // Check the return: on failure outAddr is unpopulated (stackalloc isn't zeroed), so reading
      // sin_port would yield a garbage port. The caller's catch closes fd — don't double-close here.
      if csocket.getsockname(fd, outAddr.asInstanceOf[Ptr[sockaddr]], outLen) < 0 then
        throw HttpException.connectionFailed("Failed to resolve the bound port (getsockname)")
      inet.ntohs(outAddr.sin_port).toInt

  /**
    * Accept a connection, returning the client fd (or a negative value on error / when the
    * listening socket has been closed).
    */
  def accept(serverFd: Int): Int =
    val fd = csocket.accept(
      serverFd,
      null.asInstanceOf[Ptr[sockaddr]],
      null.asInstanceOf[Ptr[socklen_t]]
    )
    if fd >= 0 then
      SocketShim.uni_socket_no_sigpipe(fd)
    fd

  /** Enable TCP keep-alive so the OS eventually reaps a silently-dropped peer (best effort). */
  def enableKeepAlive(fd: Int): Unit =
    val optval = stackalloc[CInt]()
    !optval = 1
    csocket.setsockopt(
      fd,
      csocket.SOL_SOCKET,
      csocket.SO_KEEPALIVE,
      optval.asInstanceOf[Ptr[Byte]],
      sizeof[CInt].toUInt
    )

  /**
    * Send each response piece at once rather than holding it until the previous one is acknowledged
    * (Nagle), as the JVM server does: a response written in several pieces (a file, an SSE event, a
    * WebSocket frame) would otherwise wait for the client's delayed ACK. Best effort.
    */
  def enableNoDelay(fd: Int): Unit =
    val optval = stackalloc[CInt]()
    !optval = 1
    csocket.setsockopt(
      fd,
      IPPROTO_TCP,
      TCP_NODELAY,
      optval.asInstanceOf[Ptr[Byte]],
      sizeof[CInt].toUInt
    )

  /**
    * Wait until `fd` is readable or `timeoutMillis` elapses, using `poll` (a portable millisecond
    * timeout — avoids the macOS `SO_RCVTIMEO`/`timeval` layout trap). Returns 1 if readable, 0 on
    * timeout, -1 if the peer hung up or `poll` errored.
    *
    * The `poll` call, its EINTR retry and the `revents` interpretation all live in
    * `uni_socket_shim.c`, because Windows needs `WSAPoll` and Scala Native offers no per-OS source
    * directory.
    */
  def waitReadable(fd: Int, timeoutMillis: Int): Int = SocketShim.uni_socket_wait_readable(
    fd,
    timeoutMillis
  )

  /**
    * Receive up to ChunkSize bytes. Returns an empty array on EOF or error (caller treats both as
    * end-of-connection).
    */
  def recvChunk(fd: Int): Array[Byte] =
    val buf = new Array[Byte](ChunkSize)
    val n   = recvInto(fd, buf, 0, ChunkSize)
    if n <= 0 then
      Array.emptyByteArray
    else if n == ChunkSize then
      buf
    else
      java.util.Arrays.copyOf(buf, n)

  /**
    * Receive up to `len` bytes into `buf` at `off`. Returns the number received, or 0 or less on
    * EOF or error.
    *
    * `recv` writes straight into the array: Scala Native's collectors never move objects, and `buf`
    * stays reachable from this frame while `recv` (a `@blocking` call) runs, as in Scala Native's
    * own socket code.
    */
  def recvInto(fd: Int, buf: Array[Byte], off: Int, len: Int): Int =
    if len <= 0 then
      0
    else
      csocket.recv(fd, buf.at(off), len.toUInt, 0).toInt

  /**
    * Send all of `data`, looping over partial writes. Returns false if the peer is gone.
    */
  def sendAll(fd: Int, data: Array[Byte]): Boolean = sendAll(fd, data, 0, data.length)

  /**
    * Send all of `data[off, off + len)` straight from the array (see [[recvInto]]), looping over
    * partial writes. False if the peer is gone.
    */
  def sendAll(fd: Int, data: Array[Byte], off: Int, len: Int): Boolean =
    var pos   = off
    val total = off + len
    var ok    = true
    while ok && pos < total do
      val w = csocket.send(fd, data.at(pos), (total - pos).toUInt, SendFlags)
      if w <= 0 then
        ok = false
      else
        pos += w.toInt
    ok

  /** `closesocket` on Windows: the CRT's `close` links there but only knows file descriptors. */
  def close(fd: Int): Unit = SocketShim.uni_socket_close(fd)

  /** Shut down both directions, unblocking any thread parked in `recv` on this fd. */
  def shutdown(fd: Int): Unit = csocket.shutdown(fd, csocket.SHUT_RDWR)

end NativeSocket

/** A [[SocketConnection]] over a POSIX socket file descriptor. */
private[http] class NativeSocketConnection(val fd: Int) extends SocketConnection:

  override def read(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int =
    // Bytes are usually already waiting (the poller just reported the socket readable, or the next
    // request is in flight), so read them first and poll only when there are none.
    val ready =
      if len <= 0 then
        -3
      else
        SocketShim.uni_socket_recv_ready(fd, buf.at(off), len)
    if ready > 0 then
      ready
    else if ready == 0 || ready == -1 then
      -1 // EOF or a failed socket
    else if ready == -2 && timeoutMillis == 0 then
      0 // Nothing waiting, and the caller won't wait
    else
      waitAndRecv(buf, off, len, timeoutMillis)

  private def waitAndRecv(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int =
    NativeSocket.waitReadable(fd, timeoutMillis) match
      case 1 =>
        val n = NativeSocket.recvInto(fd, buf, off, len)
        if n > 0 then
          n
        else
          -1 // a readable socket with no bytes is EOF
      case 0 =>
        0
      case _ =>
        -1

  override def write(data: Array[Byte], off: Int, len: Int): Boolean = NativeSocket.sendAll(
    fd,
    data,
    off,
    len
  )

  // stop() may interrupt a connection its worker has just closed. The fd number may then belong to
  // another socket already, so shutdown and close must not run after close.
  private var closed = false

  override def interrupt(): Unit = synchronized {
    if !closed then
      NativeSocket.shutdown(fd)
  }

  override def close(): Unit = synchronized {
    if !closed then
      closed = true
      NativeSocket.close(fd)
  }

end NativeSocketConnection

/** A listening POSIX socket. */
private[http] class NativeSocketListener(host: String, requestedPort: Int, backlog: Int)
    extends SocketListener:

  private val (serverFd, boundPort) = NativeSocket.bindAndListen(host, requestedPort, backlog)

  override def port: Int = boundPort

  override def accept(): SocketConnection =
    val clientFd = NativeSocket.accept(serverFd)
    if clientFd < 0 then
      null
    else
      NativeSocket.enableKeepAlive(clientFd)
      NativeSocket.enableNoDelay(clientFd)
      NativeSocketConnection(clientFd)

  override def close(): Unit = NativeSocket.close(serverFd)

end NativeSocketListener
