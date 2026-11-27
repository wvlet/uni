package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.encoding.ValueDecoder
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.cloud.parquet.format.Encoding
import wvlet.uni.cloud.parquet.format.MetadataDecoder
import wvlet.uni.cloud.parquet.format.MetadataEncoder
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

object MalformedPageTest:
  case class Value(id: Long) derives Weaver

  /**
    * A one-column, one-page file whose page header claims `uncompressedSize`, with the footer
    * rewritten to match the new header's length
    */
  def withUncompressedSize(codec: CompressionCodec, uncompressedSize: Int): Array[Byte] =
    val file = Parquet.writeBytes(
      (0 until 100).map(i => Value(i)),
      ParquetWriteOptions.default.withCompression(codec).noDictionary()
    )
    val meta   = Parquet.open(file).metadata
    val chunk  = meta.rowGroups.head.columns.head
    val r      = ByteReader(file, chunk.dataPageOffset.toInt, file.length)
    val header = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
    val body   = file.slice(r.pos, r.pos + header.compressedSize)
    val page = MetadataEncoder.pageHeader(header.copy(uncompressedSize = uncompressedSize)) ++ body
    val footer = MetadataEncoder.fileMetaData(
      meta.copy(rowGroups =
        IndexedSeq(
          meta
            .rowGroups
            .head
            .copy(columns =
              IndexedSeq(
                chunk.copy(
                  dataPageOffset = 4,
                  totalCompressedSize = page.length,
                  offsetIndexOffset = None,
                  offsetIndexLength = None,
                  columnIndexOffset = None,
                  columnIndexLength = None
                )
              )
            )
        )
      )
    )
    val n   = footer.length
    val len = Array[Byte](n.toByte, (n >>> 8).toByte, (n >>> 16).toByte, (n >>> 24).toByte)
    "PAR1".getBytes("UTF-8") ++ page ++ footer ++ len ++ "PAR1".getBytes("UTF-8")

  end withUncompressedSize

end MalformedPageTest

/** Corrupt files the fuzz targets in [[ParquetFuzzTest]] found */
class MalformedPageTest extends UniTest:
  import MalformedPageTest.*
  FileSystemInit.init()

  test("a page claiming more bytes than its codec can decode to fails before allocating") {
    for codec <- Seq(
        CompressionCodec.SNAPPY,
        CompressionCodec.ZSTD,
        CompressionCodec.LZ4_RAW,
        CompressionCodec.GZIP
      )
    do
      // A valid file first, so the rewrite itself is not what fails
      Parquet.readRows(withUncompressedSize(codec, 800)).size shouldBe 100
      val e = intercept[ParquetException] {
        Parquet.readRows(withUncompressedSize(codec, Int.MaxValue - 64))
      }
      e.getMessage shouldContain "cannot hold"
  }

  test("a byte-array encoding on a column of booleans is unsupported, not a ClassCastException") {
    for enc <- Seq(Encoding.DELTA_BYTE_ARRAY, Encoding.DELTA_LENGTH_BYTE_ARRAY) do
      val out = ValueDecoder.newBuffer(PhysicalType.BOOLEAN, 4)
      val e   = intercept[ParquetException] {
        ValueDecoder.decode(
          enc,
          PhysicalType.BOOLEAN,
          0,
          Array[Byte](0, 0, 0, 0),
          0,
          4,
          1,
          out,
          None
        )
      }
      e.getMessage shouldContain "Unsupported"
  }

end MalformedPageTest
