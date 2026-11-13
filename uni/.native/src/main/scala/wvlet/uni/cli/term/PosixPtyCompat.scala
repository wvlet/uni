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
import scala.scalanative.unsafe.*

/**
  * Bindings to `uni_pty_shim.c`
  */
@extern
private[term] object PtyShim:
  def uni_pty_supported(): CInt = extern

  def uni_pty_spawn(
      file: CString,
      argv: Ptr[CString],
      envp: Ptr[CString],
      cwd: CString,
      cols: CInt,
      rows: CInt,
      masterFd: Ptr[CInt]
  ): CInt = extern

  /**
    * `@blocking` parks this thread at a GC safepoint while it waits, so that a collection started
    * by another thread does not wait on it
    */
  @blocking
  def uni_pty_read(fd: CInt, buf: Ptr[Byte], len: CInt, timeoutMs: CInt): CInt = extern

  @blocking
  def uni_pty_write(fd: CInt, buf: Ptr[Byte], len: CInt): CInt = extern

  def uni_pty_resize(fd: CInt, cols: CInt, rows: CInt): CInt = extern

  @blocking
  def uni_pty_wait(pid: CInt): CInt = extern

  def uni_pty_kill(pid: CInt, sig: CInt): CInt = extern
  def uni_pty_close(fd: CInt): CInt            = extern
  def uni_pty_strerror(err: CInt): CString     = extern

end PtyShim

private[term] object PosixPtyCompat:
  def unsupportedReason: Option[String] =
    if PtyShim.uni_pty_supported() == 1 then
      None
    else
      Some("PosixPty supports Linux and macOS only")

  def ops: PosixPtyOps = NativePtyOps

/**
  * PTY calls through `uni_pty_shim.c`. Buffers passed to C are Scala arrays, which Scala Native's
  * GC does not move.
  */
private[term] object NativePtyOps extends PosixPtyOps:
  override def spawn(
      file: String,
      argv: Seq[String],
      env: Seq[String],
      cwd: Option[String],
      cols: Int,
      rows: Int
  ): (Int, Int) = Zone.acquire { implicit z =>
    def strings(values: Seq[String]): Ptr[CString] =
      val arr = alloc[CString](values.size + 1)
      values
        .zipWithIndex
        .foreach { (v, i) =>
          arr(i) = toCString(v)
        }
      arr(values.size) = null
      arr
    val masterFd = alloc[CInt](1)
    val pid      = PtyShim.uni_pty_spawn(
      toCString(file),
      strings(argv),
      strings(env),
      cwd.map(toCString(_)).orNull,
      cols,
      rows,
      masterFd
    )
    if pid < 0 then
      throw IOException(
        s"Failed to start ${file} in a PTY: ${fromCString(PtyShim.uni_pty_strerror(-pid))}"
      )
    (pid, !masterFd)
  }

  override def read(fd: Int, buf: Array[Byte], timeoutMillis: Int): Int = PtyShim.uni_pty_read(
    fd,
    buf.at(0),
    buf.length,
    timeoutMillis
  )

  override def write(fd: Int, buf: Array[Byte], len: Int): Boolean =
    len == 0 || PtyShim.uni_pty_write(fd, buf.at(0), len) == 0

  override def resize(fd: Int, cols: Int, rows: Int): Unit = PtyShim.uni_pty_resize(fd, cols, rows)

  override def waitFor(pid: Int): Int =
    val status = PtyShim.uni_pty_wait(pid)
    if status == -1 then
      -1
    else
      PosixPtyOps.exitCode(status)

  override def signal(pid: Int, sig: Int): Unit = PtyShim.uni_pty_kill(pid, sig)
  override def close(fd: Int): Unit             = PtyShim.uni_pty_close(fd)

end NativePtyOps
