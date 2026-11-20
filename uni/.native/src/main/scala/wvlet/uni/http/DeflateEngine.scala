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

import scala.scalanative.libc.stdlib
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import java.util.zip.Deflater

/**
  * The deflate stream behind a pooled response compressor, on zlib directly. Scala Native's
  * `Deflater` always asks zlib for a 32 KB window and memory level 9, so `deflateReset` cleared a
  * 128 KB hash table for every response, which cost more CPU than compressing a few KB. Here the
  * window and hash table fit the body: [[DeflateEngine.windowBitsFor]] picks the smallest window
  * that holds it, and the memory level follows. Bodies of 16 KB and more get zlib's defaults, which
  * the JDK uses. If zlib refuses the stream, a `Deflater` takes over.
  */
private[http] final class DeflateEngine(raw: Boolean, level: Int, windowBits: Int):
  import DeflateEngine.*

  private var stream: Ptr[Byte] = open()
  // Used when zlib refused the stream (a z_stream layout or version mismatch)
  private val fallback: Deflater =
    if stream == null then
      Deflater(level, raw)
    else
      null

  /** Whether zlib took the stream; false means the Deflater fallback runs */
  def direct: Boolean = fallback == null

  // Keeps the array zlib is reading reachable; the GC doesn't move objects
  private var input: Array[Byte] = null
  private var finishing          = false
  private var done               = false

  private def open(): Ptr[Byte] =
    val s = stdlib.calloc(1.toUSize, Layout.size.toUSize)
    // Negative window bits ask zlib for a raw stream, framed by the caller (gzip)
    val bits =
      if raw then
        -windowBits
      else
        windowBits
    val err = Zlib
      .api
      .deflateInit2_(
        s,
        level,
        ZDeflated,
        bits,
        memLevelFor(windowBits),
        ZDefaultStrategy,
        Zlib.api.zlibVersion(),
        Layout.size
      )
    if err == ZOk then
      s
    else
      stdlib.free(s)
      null

  def reset(): Unit =
    if fallback != null then
      fallback.reset()
    else
      Zlib.api.deflateReset(stream)
      availIn = 0
      input = null
      finishing = false
      done = false

  def needsInput: Boolean =
    if fallback != null then
      fallback.needsInput()
    else
      availIn == 0

  def setInput(b: Array[Byte], off: Int, len: Int): Unit =
    if fallback != null then
      fallback.setInput(b, off, len)
    else
      input = b
      !(stream + Layout.nextIn).asInstanceOf[Ptr[Ptr[Byte]]] = b.at(off)
      availIn = len

  def finish(): Unit =
    if fallback != null then
      fallback.finish()
    else
      finishing = true

  def finished: Boolean =
    if fallback != null then
      fallback.finished()
    else
      done

  def deflate(out: Array[Byte], off: Int, len: Int): Int =
    if fallback != null then
      fallback.deflate(out, off, len)
    else
      !(stream + Layout.nextOut).asInstanceOf[Ptr[Ptr[Byte]]] = out.at(off)
      availOut = len
      val err = Zlib
        .api
        .deflate(
          stream,
          if finishing then
            ZFinish
          else
            ZNoFlush
        )
      if err == ZStreamEnd then
        done = true
      else if err != ZOk && err != ZBufError then
        throw IllegalStateException(s"zlib deflate failed: ${err}")
      len - availOut

  def end(): Unit =
    if fallback != null then
      fallback.end()
    else if stream != null then
      Zlib.api.deflateEnd(stream)
      stdlib.free(stream)
      stream = null
      input = null

  private def availIn: Int = (!(stream + Layout.availIn).asInstanceOf[Ptr[CUnsignedInt]]).toInt
  private def availIn_=(n: Int): Unit =
    !(stream + Layout.availIn).asInstanceOf[Ptr[CUnsignedInt]] = n.toUInt

  private def availOut: Int = (!(stream + Layout.availOut).asInstanceOf[Ptr[CUnsignedInt]]).toInt
  private def availOut_=(n: Int): Unit =
    !(stream + Layout.availOut).asInstanceOf[Ptr[CUnsignedInt]] = n.toUInt

end DeflateEngine

private[http] object DeflateEngine:
  // zlib accepts 9 to 15 window bits for raw and zlib-wrapped streams
  final val MinWindowBits = 9
  final val MaxWindowBits = 15

  // zlib keeps this much lookahead, so a match reaches back at most window - MinLookahead bytes
  private final val MinLookahead = 262

  /** The smallest window whose match distance covers `length` bytes */
  def windowBitsFor(length: Int): Int =
    val bits = 32 - Integer.numberOfLeadingZeros(math.max(length + MinLookahead, 1) - 1)
    math.min(MaxWindowBits, math.max(MinWindowBits, bits))

  /** A hash table about as large as the window: zlib's default 8 for a 32 KB window */
  def memLevelFor(windowBits: Int): Int = math.max(1, windowBits - 7)

  private final val ZOk              = 0
  private final val ZStreamEnd       = 1
  private final val ZBufError        = -5
  private final val ZNoFlush         = 0
  private final val ZFinish          = 4
  private final val ZDeflated        = 8
  private final val ZDefaultStrategy = 0

  /**
    * Byte offsets of the z_stream fields used here, and its size. `uInt` is 32 bits everywhere;
    * `uLong` and pointers follow the platform (LP64, Windows' LLP64, or 32-bit). zlib checks the
    * size in deflateInit2_, so a wrong layout falls back to `Deflater` instead of corrupting
    * memory.
    */
  private object Layout:
    private val ptr =
      if LinktimeInfo.is32BitPlatform then
        4
      else
        8

    private val ulong =
      if LinktimeInfo.is32BitPlatform || LinktimeInfo.isWindows then
        4
      else
        8

    private def align(n: Int, a: Int): Int = (n + a - 1) / a * a

    val nextIn: Int      = 0
    val availIn: Int     = ptr
    private val totalIn  = align(availIn + 4, ulong)
    val nextOut: Int     = align(totalIn + ulong, ptr)
    val availOut: Int    = nextOut + ptr
    private val totalOut = align(availOut + 4, ulong)
    // msg, state, zalloc, zfree, opaque: five pointers
    private val dataType = align(totalOut + ulong, ptr) + 5 * ptr
    private val adler    = align(dataType + 4, ulong)
    val size: Int        = align(adler + 2 * ulong, ptr)

end DeflateEngine

/** The zlib functions DeflateEngine calls. Windows names the library zlib, others z. */
@extern
private[http] trait ZlibApi:
  def zlibVersion(): CString = extern
  def deflateInit2_(
      strm: Ptr[Byte],
      level: CInt,
      method: CInt,
      windowBits: CInt,
      memLevel: CInt,
      strategy: CInt,
      version: CString,
      streamSize: CInt
  ): CInt = extern

  def deflate(strm: Ptr[Byte], flush: CInt): CInt = extern
  def deflateReset(strm: Ptr[Byte]): CInt         = extern
  def deflateEnd(strm: Ptr[Byte]): CInt           = extern

private[http] object Zlib:
  @extern
  @link("z")
  private object Posix extends ZlibApi

  @extern
  @link("zlib")
  private object Windows extends ZlibApi

  // A link-time constant, so only one of the libraries is linked
  val api: ZlibApi =
    if LinktimeInfo.isWindows then
      Windows
    else
      Posix
