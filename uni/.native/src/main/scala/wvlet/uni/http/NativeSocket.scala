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

import scala.scalanative.libc.errno as libcErrno
import scala.scalanative.libc.string as cstring
import scala.scalanative.posix.arpa.inet
import scala.scalanative.posix.errno as posixErrno
import scala.scalanative.posix.netinet.in.{in_addr, sockaddr_in}
import scala.scalanative.posix.netinet.inOps.*
import scala.scalanative.posix.poll.*
import scala.scalanative.posix.pollOps.*
import scala.scalanative.posix.sys.socket as csocket
import scala.scalanative.posix.sys.socket.{sockaddr, socklen_t}
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/**
  * Thin wrappers over POSIX TCP sockets (libc, via Scala Native's posixlib) for the Native HTTP
  * server. No external dependency or linker flag is required.
  */
private[http] object NativeSocket:

  private final val ChunkSize = 8192

  /**
    * Create a listening TCP socket bound to host:port (port 0 = OS-assigned). Returns the socket fd
    * and the actually-bound port.
    */
  def bindAndListen(host: String, port: Int, backlog: Int): (Int, Int) =
    if port < 0 || port > 65535 then
      throw HttpException.connectionFailed(s"Invalid port: ${port}")
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
      unistd.close(fd)
      throw HttpException.connectionFailed(s"Failed to bind to ${host}:${port}")

    if csocket.listen(fd, backlog) < 0 then
      unistd.close(fd)
      throw HttpException.connectionFailed(s"Failed to listen on ${host}:${port}")

    (fd, resolveBoundPort(fd, port))

  end bindAndListen

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
        unistd.close(fd)
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
    */
  def waitReadable(fd: Int, timeoutMillis: Int): Int =
    val fds = stackalloc[struct_pollfd]()
    fds.fd = fd
    fds.events = POLLIN.toShort
    fds.revents = 0.toShort
    var rc = poll(fds, 1.toUInt, timeoutMillis)
    // Retry on EINTR (a signal) rather than mistaking it for a dead peer.
    while rc < 0 && libcErrno.errno == posixErrno.EINTR do
      fds.revents = 0.toShort
      rc = poll(fds, 1.toUInt, timeoutMillis)
    if rc < 0 then
      -1
    else if rc == 0 then
      0
    else
      val re = fds.revents.toInt
      // Prefer draining pending data over reporting a hangup: a peer that sent a final request then
      // closed shows POLLIN (+ possibly POLLHUP); returning 1 lets recv read the data, then EOF.
      if (re & POLLIN) != 0 then
        1
      else if (re & (POLLHUP | POLLERR | POLLNVAL)) != 0 then
        -1
      else
        0

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

  def close(fd: Int): Unit = unistd.close(fd)

end NativeSocket
