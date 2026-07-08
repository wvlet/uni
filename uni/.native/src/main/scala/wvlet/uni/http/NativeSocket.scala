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
import scala.scalanative.posix.netinet.in.{in_addr, sockaddr_in}
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
  * Thin wrappers over POSIX TCP sockets (libc, via Scala Native's posixlib) for the Native HTTP
  * server. No external dependency or linker flag is required.
  */
private[http] object NativeSocket:

  private final val ChunkSize = 8192

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
      SocketShim.uni_socket_close(fd)
      throw HttpException.connectionFailed(s"Failed to bind to ${host}:${port}")

    if csocket.listen(fd, backlog) < 0 then
      SocketShim.uni_socket_close(fd)
      throw HttpException.connectionFailed(s"Failed to listen on ${host}:${port}")

    (fd, resolveBoundPort(fd, port))

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
      // sin_port would yield a garbage port.
      if csocket.getsockname(fd, outAddr.asInstanceOf[Ptr[sockaddr]], outLen) < 0 then
        SocketShim.uni_socket_close(fd)
        throw HttpException.connectionFailed("Failed to resolve the bound port (getsockname)")
      inet.ntohs(outAddr.sin_port).toInt

  /**
    * Accept a connection, returning the client fd (or a negative value on error / when the
    * listening socket has been closed).
    */
  def accept(serverFd: Int): Int = csocket.accept(
    serverFd,
    null.asInstanceOf[Ptr[sockaddr]],
    null.asInstanceOf[Ptr[socklen_t]]
  )

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
    val cbuf = stackalloc[Byte](ChunkSize)
    val n    = csocket.recv(fd, cbuf.asInstanceOf[Ptr[Byte]], ChunkSize.toUInt, 0)
    if n <= 0 then
      Array.emptyByteArray
    else
      val len = n.toInt
      val arr = new Array[Byte](len)
      var i   = 0
      while i < len do
        arr(i) = cbuf(i)
        i += 1
      arr

  /**
    * Send all of `data`, looping over partial writes. Returns false if the peer is gone.
    */
  def sendAll(fd: Int, data: Array[Byte]): Boolean =
    val chunk = stackalloc[Byte](ChunkSize)
    var pos   = 0
    var ok    = true
    val total = data.length
    while ok && pos < total do
      val n = math.min(ChunkSize, total - pos)
      var i = 0
      while i < n do
        chunk(i) = data(pos + i)
        i += 1
      var sent = 0
      while ok && sent < n do
        val w = csocket.send(fd, (chunk + sent).asInstanceOf[Ptr[Byte]], (n - sent).toUInt, 0)
        if w <= 0 then
          ok = false
        else
          sent += w.toInt
      pos += n
    ok

  /** `closesocket` on Windows: the CRT's `close` links there but only knows file descriptors. */
  def close(fd: Int): Unit = SocketShim.uni_socket_close(fd)

  /** Shut down both directions, unblocking any thread parked in `recv` on this fd. */
  def shutdown(fd: Int): Unit = csocket.shutdown(fd, csocket.SHUT_RDWR)

end NativeSocket
