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
package wvlet.uni.io

import java.io.{EOFException, IOException, InputStream}
import java.util.zip.{CRC32, DataFormatException, Inflater, ZipException}

private[uni] object GzipStreams:
  /**
    * A decoder of the gzip stream `in` that reads every member, as the JVM's GZIPInputStream does.
    * Scala Native's GZIPInputStream stops after the first member.
    */
  def decoder(in: InputStream, bufferSize: Int): InputStream = GzipMembersInputStream(
    in,
    bufferSize
  )

/**
  * Decodes each member of a gzip stream (RFC 1952) in turn and checks each trailer. As the JVM
  * does, bytes after a member that don't start a valid member are ignored.
  *
  * Every zlib call reads or writes at most `bufferSize` bytes. Before Scala Native 0.5.13, javalib
  * on Windows truncated zlib calls over 64 KiB (scala-native#5269).
  */
private class GzipMembersInputStream(in: InputStream, bufferSize: Int) extends InputStream:
  import GzipMembersInputStream.*

  private val buf        = new Array[Byte](bufferSize)
  private var pos        = 0
  private var lim        = 0
  private val inflater   = Inflater(true)
  private val crc        = CRC32()
  private var memberSize = 0L
  private var eos        = false
  private var closed     = false
  // While true, readByte adds each byte to `crc`, for the FHCRC header check
  private var inHeader = false
  private val one      = new Array[Byte](1)

  // Like GZIPInputStream, fail on construction when the input doesn't start with a gzip header
  try
    readHeader()
  catch
    case e: Throwable =>
      inflater.end()
      throw e

  override def read(): Int =
    if read(one, 0, 1) < 0 then
      -1
    else
      one(0) & 0xff

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    if closed then
      throw IOException("Stream closed")
    if off < 0 || len < 0 || len > b.length - off then
      throw IndexOutOfBoundsException()
    var n = 0
    while n == 0 && len > 0 && !eos do
      if inflater.finished() then
        // Return what the member didn't use to the buffer: it holds the trailer
        pos = lim - inflater.getRemaining()
        readTrailer()
        nextMember()
      else if inflater.needsInput() then
        if pos == lim && !fill() then
          throw EOFException("Unexpected end of gzip input stream")
        inflater.setInput(buf, pos, lim - pos)
        pos = lim
      else
        n = inflate(b, off, math.min(len, bufferSize))
        if n == 0 && inflater.needsDictionary() then
          throw ZipException("Unexpected preset dictionary in gzip input stream")
    if n == 0 && eos then
      -1
    else
      n

  override def available(): Int =
    if eos || closed then
      0
    else
      1

  override def close(): Unit =
    if !closed then
      closed = true
      inflater.end()
      in.close()

  private def inflate(b: Array[Byte], off: Int, len: Int): Int =
    val n =
      try
        inflater.inflate(b, off, len)
      catch
        case e: DataFormatException =>
          throw ZipException(Option(e.getMessage).getOrElse("Invalid gzip data"))
    crc.update(b, off, n)
    memberSize += n
    n

  /** Refill the buffer; false at the end of `in` */
  private def fill(): Boolean =
    val n = in.read(buf, 0, buf.length)
    pos = 0
    lim = math.max(n, 0)
    n > 0

  private def readByte(): Int =
    if pos == lim && !fill() then
      throw EOFException("Unexpected end of gzip input stream")
    val v = buf(pos) & 0xff
    pos += 1
    if inHeader then
      crc.update(v)
    v

  private def readIntLE(): Long =
    val b0 = readByte().toLong
    val b1 = readByte().toLong
    val b2 = readByte().toLong
    val b3 = readByte().toLong
    b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)

  private def skipBytes(n: Int): Unit =
    var i = 0
    while i < n do
      readByte()
      i += 1

  private def skipZeroTerminated(): Unit =
    while readByte() != 0 do
      (
    )

  private def readHeader(): Unit =
    crc.reset()
    inHeader = true
    if readByte() != 0x1f || readByte() != 0x8b then
      throw ZipException("Not in GZIP format")
    if readByte() != 8 then
      throw ZipException("Unsupported compression method")
    val flags = readByte()
    skipBytes(6) // MTIME, XFL, OS
    if (flags & FExtra) != 0 then
      val lo = readByte()
      val hi = readByte()
      skipBytes(lo | (hi << 8))
    if (flags & FName) != 0 then
      skipZeroTerminated()
    if (flags & FComment) != 0 then
      skipZeroTerminated()
    inHeader = false
    if (flags & FHcrc) != 0 then
      val expected = readByte() | (readByte() << 8)
      if expected != (crc.getValue & 0xffff) then
        throw ZipException("Corrupt GZIP header")
    inflater.reset()
    crc.reset()
    memberSize = 0

  private def readTrailer(): Unit =
    val expectedCrc  = readIntLE()
    val expectedSize = readIntLE()
    if expectedCrc != crc.getValue then
      throw ZipException("Corrupt GZIP trailer")
    if expectedSize != (memberSize & 0xffffffffL) then
      throw ZipException("Corrupt GZIP trailer")

  private def nextMember(): Unit =
    if pos == lim && !fill() then
      eos = true
    else
      try
        readHeader()
      catch
        case _: IOException =>
          eos = true

end GzipMembersInputStream

private object GzipMembersInputStream:
  private final val FHcrc    = 2
  private final val FExtra   = 4
  private final val FName    = 8
  private final val FComment = 16
