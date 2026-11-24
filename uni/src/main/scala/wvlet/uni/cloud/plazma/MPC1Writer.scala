package wvlet.uni.cloud.plazma

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.Value.ArrayValue
import wvlet.uni.msgpack.spi.Value.BinaryValue
import wvlet.uni.msgpack.spi.Value.MapValue
import wvlet.uni.msgpack.spi.Value.NilValue
import wvlet.uni.msgpack.spi.Value.StringValue

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Options of [[MPC1Writer]].
  *
  * @param compression
  *   the codec of every block. GZIP by default: other MPC1 readers gunzip the column-name block
  *   whatever the codec, so they can read only gzip files
  * @param metadata
  *   application metadata stored in the header; written as an empty array when not set
  */
case class MPC1WriteOptions(
    compression: MPC1Compression = MPC1Compression.GZIP,
    metadata: Option[Value] = None
):
  def withCompression(c: MPC1Compression): MPC1WriteOptions = copy(compression = c)
  def noCompression(): MPC1WriteOptions        = copy(compression = MPC1Compression.NONE)
  def withMetadata(m: Value): MPC1WriteOptions = copy(metadata = Some(m))
  def noMetadata(): MPC1WriteOptions           = copy(metadata = None)

object MPC1WriteOptions:
  val default: MPC1WriteOptions = MPC1WriteOptions()

/**
  * Writes rows into an MPC1 file. Columns are discovered from the rows: each distinct key becomes a
  * column, and rows without a value for a column (absent or nil) store nil there.
  *
  * {{{
  * val writer = MPC1.newWriter()
  * writer.add(Seq("time" -> LongValue(1700000000L), "user" -> StringValue("a")))
  * writer.writeTo(IOPath.parse("part.mpc1"))
  * }}}
  *
  * The writer holds the uncompressed column data in memory until the file is written, so split
  * large outputs into several files. Columns seen in few rows are kept as (row, value) pairs until
  * they become dense enough, so wide, sparse rows don't fill memory with nils.
  */
class MPC1Writer(options: MPC1WriteOptions = MPC1WriteOptions.default):
  import MPC1Writer.*

  private val columnMap         = mutable.LinkedHashMap.empty[String, ColumnBuffer]
  private var recordCount: Long = 0
  private var finished          = false

  /** The number of rows added so far */
  def rowCount: Long = recordCount

  /** The columns seen so far, in first-seen order */
  def columnNames: Seq[String] = columnMap.keys.toSeq

  /** Adds a row; its keys must be strings (or UTF-8 binaries) */
  def add(row: MapValue): Unit = add(row.entries)

  /**
    * Adds a row of (column, value) pairs. Nil values are skipped; when a column repeats, its first
    * non-nil value is kept.
    */
  def add(row: Iterable[(Value, Value)]): Unit =
    checkOpen()
    val touched = mutable.ArrayBuffer.empty[ColumnBuffer]
    row.foreach { (k, v) =>
      val col = columnMap.getOrElseUpdate(columnName(k), ColumnBuffer())
      if v != NilValue && col.lastRow != recordCount then
        col.lastRow = recordCount
        col.add(recordCount, v)
        if !col.isDense then
          touched += col
    }
    recordCount += 1
    touched.foreach { col =>
      if col.count > DenseMinCount && col.count.toDouble / recordCount > DenseRatio then
        col.toDense()
    }

  /** Adds a row of (column name, value) pairs */
  def addRow(row: (String, Value)*): Unit = add(row.map((k, v) => (StringValue(k): Value) -> v))

  /** Encodes the file. The writer accepts no more rows afterwards. */
  def toByteArray: Array[Byte] =
    val out = ByteArrayOutputStream()
    writeTo(out)
    out.toByteArray

  def writeTo(path: IOPath): Unit = FileSystem.writeBytes(path, toByteArray)

  /** Writes the file to `out`, leaving it open */
  def writeTo(out: OutputStream): Unit =
    finished = true
    val compression = options.compression
    // Smaller blocks first, as the original writer does
    val blocks = columnMap
      .toIndexedSeq
      .map { (name, col) =>
        val (data, len) = col.packed(recordCount)
        name -> MPC1Format.compress(compression, data, len)
      }
      .sortBy(_._2.length)
    val names = PureScalaBufferPacker()
    blocks.foreach((name, _) => names.packString(name))
    val nameBlock  = MPC1Format.compress(compression, names.writtenArray, names.writtenSize)
    val blockSizes = (nameBlock +: blocks.map(_._2)).map(_.length.toLong)
    val header     = MPC1Format.encodeHeader(
      compression,
      options.metadata.getOrElse(ArrayValue(IndexedSeq.empty)),
      blockSizes
    )
    out.write(header)
    out.write(nameBlock)
    blocks.foreach((_, block) => out.write(block))

  private def checkOpen(): Unit =
    if finished then
      throw IllegalStateException("MPC1Writer accepts no rows after the file is written")

end MPC1Writer

private object MPC1Writer:
  // A sparse column turns dense once it has more than 100 values in over 30% of the rows
  private val DenseMinCount = 100
  private val DenseRatio    = 0.3

  private def columnName(k: Value): String =
    k match
      case StringValue(s) =>
        s
      case BinaryValue(b) =>
        String(b, StandardCharsets.UTF_8)
      case other =>
        throw IllegalArgumentException(s"MPC1 column names must be strings: ${other}")

  /**
    * The values of one column. A sparse column packs its values together and remembers the row and
    * end offset of each; a dense column packs one value per row, nil-filled.
    */
  private class ColumnBuffer:
    var lastRow: Long = -1
    var count: Long   = 0

    private val packer        = PureScalaBufferPacker()
    private var filled: Long  = 0
    private var denseMode     = false
    private var rows          = new Array[Long](4)
    private var ends          = new Array[Int](4)
    private var sparseEntries = 0

    def isDense: Boolean = denseMode

    def add(row: Long, v: Value): Unit =
      count += 1
      if denseMode then
        fillNil(packer, row)
        packer.packValue(v)
        filled += 1
      else
        packer.packValue(v)
        if sparseEntries == rows.length then
          rows = java.util.Arrays.copyOf(rows, rows.length * 2)
          ends = java.util.Arrays.copyOf(ends, ends.length * 2)
        rows(sparseEntries) = row
        ends(sparseEntries) = packer.writtenSize
        sparseEntries += 1

    private def fillNil(p: PureScalaBufferPacker, upTo: Long): Unit =
      while filled < upTo do
        p.packNil
        filled += 1

    /** Rewrites the sparse values one per row */
    def toDense(): Unit =
      if !denseMode then
        val data = packer.toByteArray
        packer.clear
        filled = 0
        var start = 0
        var i     = 0
        while i < sparseEntries do
          fillNil(packer, rows(i))
          packer.writePayload(data, start, ends(i) - start)
          filled += 1
          start = ends(i)
          i += 1
        rows = null
        ends = null
        denseMode = true

    /** The packed column of `rowCount` rows, as (array, length) */
    def packed(rowCount: Long): (Array[Byte], Int) =
      toDense()
      fillNil(packer, rowCount)
      (packer.writtenArray, packer.writtenSize)

  end ColumnBuffer

end MPC1Writer
