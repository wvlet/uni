package wvlet.uni.cloud.plazma

import wvlet.uni.io.FileSystem
import wvlet.uni.io.Gzip
import wvlet.uni.io.codec.CodecException
import wvlet.uni.io.codec.Inflate
import wvlet.uni.msgpack.impl.ByteArrayBuffer
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.msgpack.spi.OffsetUnpacker
import wvlet.uni.msgpack.spi.ReadCursor
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.ArrayValue
import wvlet.uni.msgpack.spi.Value.BinaryValue
import wvlet.uni.msgpack.spi.Value.LongValue
import wvlet.uni.msgpack.spi.Value.NilValue
import wvlet.uni.msgpack.spi.Value.StringValue

import java.nio.charset.StandardCharsets

/** Compression of the column blocks of an MPC1 file */
enum MPC1Compression(val code: String):
  case NONE extends MPC1Compression("none")
  case GZIP extends MPC1Compression("gzip")

object MPC1Compression:
  def fromCode(code: String): MPC1Compression = MPC1Compression
    .values
    .find(_.code.equalsIgnoreCase(code))
    .getOrElse(throw MPC1Exception.corrupt(s"unknown compression type '${code}'"))

/** Thrown when an MPC1 file is malformed or truncated */
class MPC1Exception(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object MPC1Exception:
  private[plazma] def corrupt(message: String): MPC1Exception = MPC1Exception(
    s"Corrupt MPC1 data: ${message}"
  )

/**
  * The header of an MPC1 file.
  *
  * @param compression
  *   the codec of every block, including the column-name block
  * @param metadata
  *   the application metadata stored in the header; an empty array when the writer set none
  * @param blockSizes
  *   the stored size of each block: the column-name block first, then one per column
  * @param dataOffset
  *   the file offset of the first block
  */
case class MPC1Header(
    compression: MPC1Compression,
    metadata: Value,
    blockSizes: IndexedSeq[Long],
    dataOffset: Int
)

/**
  * A column of an MPC1 file
  *
  * @param offset
  *   the file offset of the column block
  * @param size
  *   the stored (compressed) size of the column block
  */
case class MPC1Column(name: String, offset: Long, size: Long)

/**
  * The MPC1 layout. A file is
  *
  * {{{
  * c6 'M' 'P' 0a | header size (int32, big-endian) | header | name block | column block ...
  * }}}
  *
  * The header is the MessagePack array `["MPC1", [compression or nil], metadata, 0]` followed by
  * the size of every block in variable-byte code (7 bits per byte, most significant group first,
  * the high bit marking the last byte of a size). A file with a single block omits the sizes.
  *
  * The name block is a stream of MessagePack strings, one per column. Each column block is a stream
  * of one MessagePack value per row, nil where the row has no value. Every block is compressed on
  * its own with the header's codec.
  */
private[plazma] object MPC1Format:
  val ContainerMagic: Array[Byte] = Array(0xc6.toByte, 'M'.toByte, 'P'.toByte, 0x0a.toByte)
  val BlockMagic: Array[Byte]     = "MPC1".getBytes(StandardCharsets.US_ASCII)
  val MagicHeaderSize: Int        = 8
  val IndexType: Int              = 0
  // Large enough for the header and the name block of most files, so one read gets both
  val ExpectedMaxHeaderSize: Int = 1024

  /** Encodes block sizes in variable-byte code */
  def encodeIndex(sizes: Seq[Long], out: PureScalaBufferPacker): Unit =
    val buf = new Array[Byte](10)
    sizes.foreach { s =>
      if s <= 0 then
        throw IllegalArgumentException(s"block size must be positive: ${s}")
      var size = s
      var p    = buf.length
      while
        p -= 1
        buf(p) = (size & 0x7f).toByte
        size >>>= 7
        size > 0
      do
        (
      )
      buf(buf.length - 1) = (buf(buf.length - 1) | 0x80).toByte
      out.writePayload(buf, p, buf.length - p)
    }

  /** Decodes variable-byte block sizes; a zero size ends the list */
  def decodeIndex(buf: Array[Byte], offset: Int, len: Int): IndexedSeq[Long] =
    val result = IndexedSeq.newBuilder[Long]
    var n      = 0L
    var i      = offset
    val limit  = offset + len
    var done   = false
    while !done && i < limit do
      val b = buf(i)
      if (n >>> 57) != 0 then
        throw MPC1Exception.corrupt("block size overflows")
      n = (n << 7) | (b & 0x7f)
      if b < 0 then
        if n == 0 then
          done = true
        else
          result += n
          n = 0
      i += 1
    result.result()

  def encodeHeader(
      compression: MPC1Compression,
      metadata: Value,
      blockSizes: Seq[Long]
  ): Array[Byte] =
    val pk = PureScalaBufferPacker()
    pk.packArrayHeader(4)
    pk.packRawStringHeader(BlockMagic.length)
    pk.writePayload(BlockMagic)
    pk.packArrayHeader(1)
    compression match
      case MPC1Compression.NONE =>
        pk.packNil
      case c =>
        pk.packString(c.code)
    pk.packValue(metadata)
    pk.packInt(IndexType)
    // A single block (the name block of a file without columns) spans the rest of the file
    if blockSizes.length > 1 then
      encodeIndex(blockSizes, pk)
    val header = pk.toByteArray
    val out    = new Array[Byte](MagicHeaderSize + header.length)
    System.arraycopy(ContainerMagic, 0, out, 0, 4)
    putInt(out, 4, header.length)
    System.arraycopy(header, 0, out, MagicHeaderSize, header.length)
    out

  /** The total header size (magic included) from its first 8 bytes */
  def headerSize(prefix: Array[Byte]): Int =
    if prefix.length < MagicHeaderSize then
      throw MPC1Exception.corrupt(s"file has ${prefix.length} bytes, too short for a header")
    if !prefix.take(4).sameElements(ContainerMagic) then
      throw MPC1Exception.corrupt("not an MPC1 file (unknown container magic)")
    val size = getInt(prefix, 4)
    if size < 0 then
      throw MPC1Exception.corrupt(s"negative header size ${size}")
    MagicHeaderSize + size

  /** Parses the header from the first `headerSize(...)` bytes of a file of `fileSize` bytes */
  def parseHeader(buf: Array[Byte], fileSize: Long): MPC1Header =
    val total = headerSize(buf)
    if buf.length < total then
      throw MPC1Exception.corrupt(s"header needs ${total} bytes, but ${buf.length} were given")
    val cursor = ReadCursor(
      ByteArrayBuffer.fromArray(buf, MagicHeaderSize, total - MagicHeaderSize),
      0
    )
    val (values, consumed) =
      try
        val v = OffsetUnpacker.unpackValue(cursor)
        (v, cursor.lastReadLength)
      catch
        case e: Exception =>
          throw MPC1Exception(s"Corrupt MPC1 data: unreadable header: ${e.getMessage}", e)
    val header =
      values match
        case ArrayValue(elems) if elems.size >= 4 =>
          elems
        case other =>
          throw MPC1Exception.corrupt(s"invalid header: ${other}")
    val blockType =
      header(0) match
        case StringValue(s) =>
          s.getBytes(StandardCharsets.UTF_8)
        case BinaryValue(b) =>
          b
        case other =>
          throw MPC1Exception.corrupt(s"invalid block type: ${other}")
    if !blockType.sameElements(BlockMagic) then
      throw MPC1Exception.corrupt(
        s"unknown block type '${String(blockType, StandardCharsets.UTF_8)}'"
      )
    val compression =
      header(1) match
        case ArrayValue(Seq(NilValue)) =>
          MPC1Compression.NONE
        case ArrayValue(Seq(StringValue(code))) =>
          MPC1Compression.fromCode(code)
        case other =>
          throw MPC1Exception.corrupt(s"invalid block data: ${other}")
    header(3) match
      case LongValue(v) if v == IndexType =>
      case other                          =>
        throw MPC1Exception.corrupt(s"unknown index type ${other}")
    val indexStart = MagicHeaderSize + consumed
    val sizes      = decodeIndex(buf, indexStart, total - indexStart)
    val blockSizes =
      if sizes.isEmpty then
        IndexedSeq(fileSize - total)
      else
        sizes
    if total + blockSizes.sum > fileSize then
      throw MPC1Exception.corrupt(
        s"blocks need ${total + blockSizes.sum} bytes, but the file has ${fileSize}"
      )
    MPC1Header(compression, header(2), blockSizes, total)

  end parseHeader

  /** Decodes the names in the (decompressed) name block */
  def parseNames(block: Array[Byte]): IndexedSeq[String] =
    val names    = IndexedSeq.newBuilder[String]
    val unpacker = MessagePack.newUnpacker(block)
    while unpacker.hasNext do
      val len = unpacker.unpackRawStringHeader
      names += String(unpacker.readPayload(len), StandardCharsets.UTF_8)
    names.result()

  def compress(compression: MPC1Compression, data: Array[Byte], len: Int): Array[Byte] =
    compression match
      case MPC1Compression.NONE =>
        java.util.Arrays.copyOf(data, len)
      case MPC1Compression.GZIP =>
        if FileSystem.isBrowser then
          throw UnsupportedOperationException(
            "Writing gzip MPC1 files is not supported in browsers"
          )
        Gzip.compress(java.util.Arrays.copyOf(data, len))

  def decompress(compression: MPC1Compression, data: Array[Byte]): Array[Byte] =
    compression match
      case MPC1Compression.NONE =>
        data
      case MPC1Compression.GZIP =>
        try
          if FileSystem.isBrowser then
            Inflate.gunzip(data, data.length * 4)
          else
            Gzip.decompress(data)
        catch
          case e: CodecException =>
            throw MPC1Exception(s"Corrupt MPC1 data: gzip: ${e.getMessage}", e)
          case e: java.io.IOException =>
            throw MPC1Exception(s"Corrupt MPC1 data: gzip: ${e.getMessage}", e)

  private def putInt(buf: Array[Byte], offset: Int, v: Int): Unit =
    buf(offset) = (v >>> 24).toByte
    buf(offset + 1) = (v >>> 16).toByte
    buf(offset + 2) = (v >>> 8).toByte
    buf(offset + 3) = v.toByte

  private def getInt(buf: Array[Byte], offset: Int): Int =
    ((buf(offset) & 0xff) << 24) |
      ((buf(offset + 1) & 0xff) << 16) |
      ((buf(offset + 2) & 0xff) << 8) |
      (buf(offset + 3) & 0xff)

end MPC1Format
