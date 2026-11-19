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

/**
  * One accepted TCP connection, as [[SocketHttpServer]] and [[SocketWebSocket]] see it. Scala
  * Native implements it over POSIX file descriptors and the JVM over `java.net.Socket`, so the HTTP
  * and WebSocket code above it is shared.
  *
  * Reads come from one thread at a time. A write may run on another thread concurrently with a read
  * (an SSE event source, a WebSocket send); callers serialize writes among themselves.
  */
private[http] trait SocketConnection:

  /**
    * Read up to `len` bytes into `buf` at `off`, waiting up to `timeoutMillis` for data (-1: no
    * limit, 0: don't wait). Returns the number of bytes read (> 0), 0 on timeout, or -1 when the
    * peer closed the connection or the read failed.
    */
  def read(buf: Array[Byte], off: Int, len: Int, timeoutMillis: Int): Int

  /** Write all of `data[off, off + len)`. Returns false if the peer is gone. */
  def write(data: Array[Byte], off: Int, len: Int): Boolean

  final def write(data: Array[Byte]): Boolean = write(data, 0, data.length)

  /**
    * Wake a thread blocked reading this connection, from another thread: the read then returns -1.
    * The reading thread still owns the connection and closes it.
    */
  def interrupt(): Unit

  /**
    * Fail a write blocked on this connection, from another thread, when the peer stops reading. The
    * writing thread still closes the connection.
    */
  def abort(): Unit = interrupt()

  def close(): Unit

end SocketConnection

/** A listening socket that hands out [[SocketConnection]]s. */
private[http] trait SocketListener:
  /** The bound port, which differs from the requested one when that was 0 (ephemeral). */
  def port: Int

  /**
    * Wait for the next connection. Returns null when accept fails; after [[close]], every call
    * returns null.
    */
  def accept(): SocketConnection

  /** Stop listening, unblocking a thread waiting in [[accept]]. */
  def close(): Unit

/**
  * A connection waiting for its next request in an [[IdlePoller]]. The poller owns it until it
  * calls exactly one of these, once.
  */
private[http] trait IdleConnection:
  /** The connection is readable (a request, or the peer closing): serve it on a worker again. */
  def resume(): Unit

  /** The connection sat idle past the idle timeout, or the poller closed: close it. */
  def expire(): Unit

/**
  * Watches connections between requests, so an idle keep-alive connection holds no worker thread.
  * Scala Native implements it with epoll or kqueue.
  */
private[http] trait IdlePoller:
  /**
    * Hand `conn` (over `socket`) to the poller until it is readable or times out. Returns false
    * when the poller can't take it (it is closed, or bytes are already waiting); the caller still
    * owns the connection then.
    */
  def park(socket: SocketConnection, conn: IdleConnection): Boolean

  /** Stop watching, expiring every parked connection, and wait for the poller thread to exit. */
  def close(): Unit
