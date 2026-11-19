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

import wvlet.uni.log.LogSupport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

/**
  * An [[IdlePoller]] over epoll (Linux) or kqueue (macOS, BSD), run by one daemon thread.
  *
  * Each parked connection gets a fresh token, and the poller reports tokens, not fds: a parked
  * connection closed by its idle timeout frees its fd number for reuse. Ownership moves through
  * `parked`: whoever removes a connection's entry (the poller thread on readiness, timeout, or
  * close, or a worker whose registration failed) owns the connection from then on, so exactly one
  * side resumes or closes it.
  *
  * Idle timeouts are checked by a sweep every `tick` (a quarter of the timeout, at most 1 s), so a
  * connection closes between `idleTimeoutMillis` and `idleTimeoutMillis + tick` after it parked.
  */
private[http] class NativeIdlePoller private (
    pollerFd: Int,
    wakeReadFd: Int,
    wakeWriteFd: Int,
    idleTimeoutMillis: Int,
    name: String
) extends IdlePoller
    with LogSupport:
  import NativeIdlePoller.*

  private class Parked(val conn: IdleConnection, val deadlineNanos: Long)

  private val parked          = ConcurrentHashMap[Long, Parked]()
  private val nextToken       = AtomicLong(1L)
  private val idleNanos       = idleTimeoutMillis * 1000000L
  private val tickMillis: Int = math.max(1, math.min(1000, idleTimeoutMillis / 4))

  @volatile
  private var closed = false

  private val thread =
    val t = Thread(() => loop())
    t.setDaemon(true)
    t.setName(s"${name}-idle-poller")
    t.start()
    t

  override def park(socket: SocketConnection, conn: IdleConnection): Boolean =
    socket match
      case s: NativeSocketConnection if !closed =>
        // The caller has just found nothing to read. Bytes arriving since then still wake the
        // poller: epoll and kqueue report a socket that is readable when it is registered.
        val token = nextToken.getAndIncrement()
        parked.put(token, Parked(conn, System.nanoTime() + idleNanos))
        // Check closed after the put: close() sets it before taking every entry, so either it
        // takes this one or we see it
        if closed || SocketShim.uni_poller_add(pollerFd, s.fd, token) != 0 then
          // Take the connection back, unless the poller already owns it
          parked.remove(token) == null
        else
          true
      case _ =>
        false

  override def close(): Unit =
    if !closed then
      closed = true
      SocketShim.uni_poller_wake(wakeWriteFd)
      thread.join()

  private def loop(): Unit =
    val tokens   = stdlib.malloc(sizeof[CLongLong] * MaxEvents.toUSize).asInstanceOf[Ptr[CLongLong]]
    var nextScan = System.nanoTime() + tickMillis * 1000000L
    try
      while !closed do
        val n = SocketShim.uni_poller_wait(pollerFd, tokens, MaxEvents, tickMillis)
        if n < 0 then
          warn("The idle-connection poller failed; closing its connections")
          closed = true
        var i = 0
        while i < n do
          val token = tokens(i)
          if token == WakeToken then
            SocketShim.uni_poller_drain(wakeReadFd)
          else
            val p = parked.remove(token)
            if p != null then
              hand(p.conn.resume())
          i += 1
        val now = System.nanoTime()
        if now - nextScan >= 0 then
          nextScan = now + tickMillis * 1000000L
          expireIdle(now)
      end while
    finally
      stdlib.free(tokens.asInstanceOf[Ptr[Byte]])
      // Once closed, no park() keeps an entry, so this takes every remaining connection
      parked.forEach { (token, p) =>
        if parked.remove(token) != null then
          hand(p.conn.expire())
      }
      SocketShim.uni_socket_close(wakeReadFd)
      SocketShim.uni_socket_close(wakeWriteFd)
      SocketShim.uni_socket_close(pollerFd)
    end try

  end loop

  private def expireIdle(now: Long): Unit = parked.forEach { (token, p) =>
    if now - p.deadlineNanos >= 0 && parked.remove(token, p) then
      hand(p.conn.expire())
  }

  // A failing callback must not stop the poller, which still holds the other connections
  private inline def hand(body: => Unit): Unit =
    try
      body
    catch
      case NonFatal(e) =>
        warn(s"Error handing back an idle connection: ${e.getMessage}", e)

end NativeIdlePoller

private[http] object NativeIdlePoller:
  private final val WakeToken = 0L
  private final val MaxEvents = 256

  /** A poller on Linux, macOS, and the BSDs; None elsewhere (Windows), or if creating one fails. */
  def create(idleTimeoutMillis: Int, name: String): Option[NativeIdlePoller] =
    val wake = stackalloc[CInt](2)
    val fd   = SocketShim.uni_poller_create(wake)
    if fd < 0 then
      None
    else
      Some(NativeIdlePoller(fd, wake(0), wake(1), idleTimeoutMillis, name))
