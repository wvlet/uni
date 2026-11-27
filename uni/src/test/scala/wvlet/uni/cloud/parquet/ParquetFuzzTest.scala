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
package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.codec.Codecs
import wvlet.uni.cloud.parquet.encoding.BitPacking
import wvlet.uni.cloud.parquet.encoding.NullMask
import wvlet.uni.cloud.parquet.encoding.RleBitPackedDecoder
import wvlet.uni.cloud.parquet.encoding.ValueDecoder
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.cloud.parquet.format.Encoding
import wvlet.uni.cloud.parquet.format.MetadataDecoder
import wvlet.uni.cloud.parquet.format.PageType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.thrift.CompactProtocol
import wvlet.uni.io.codec.Bytes
import wvlet.uni.io.FileSystemInit
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.FuzzConfig
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverContext
import wvlet.uni.weaver.codec.AnyWeaver

object ParquetFuzzTest:
  case class Inner(x: Int, tag: Option[String]) derives Weaver
  case class Row(
      id: Long,
      name: String,
      score: Option[Double],
      flag: Boolean,
      small: Int,
      tags: Seq[String],
      counts: Map[String, Long],
      inner: Option[Inner]
  ) derives Weaver

  /** Values of one page, after its levels, with the levels of an unnested optional column */
  case class Page(
      encoding: Encoding,
      tpe: PhysicalType,
      typeLength: Int,
      numValues: Int,
      values: Array[Byte],
      defLevels: Option[Array[Byte]],
      maxDef: Int
  )

  /** Cuts the pages out of a file, decompressed, as seeds for the page decoder targets */
  def pages(file: Array[Byte]): Seq[Page] =
    val f = Parquet.open(file)
    try
      val leaves = f.schema.fields.flatMap(column.FilteredBatches.leaves)
      for
        rg            <- f.metadata.rowGroups
        (chunk, leaf) <- rg.columns.zip(leaves)
        page          <- chunkPages(
          file,
          chunk.codec,
          chunk.dictionaryPageOffset.filter(_ > 0).getOrElse(chunk.dataPageOffset),
          chunk.totalCompressedSize,
          leaf
        )
      yield page
    finally
      f.close()

  private def chunkPages(
      file: Array[Byte],
      codec: CompressionCodec,
      offset: Long,
      size: Long,
      leaf: ParquetField.Primitive
  ): Seq[Page] =
    val out = Seq.newBuilder[Page]
    var pos = offset.toInt
    val end = (offset + size).toInt
    def page(enc: Encoding, n: Int, values: Array[Byte], defs: Option[Array[Byte]]) =
      out += Page(enc, leaf.physicalType, leaf.typeLength, n, values, defs, leaf.maxDefinitionLevel)
    // Seeds only: a chunk laid out in a way this helper doesn't follow keeps the pages cut so far
    try
      while pos < end do
        val r         = ByteReader(file, pos, file.length)
        val h         = MetadataDecoder.pageHeader(CompactProtocol.readStruct(r))
        val bodyStart = r.pos
        pos = bodyStart + h.compressedSize
        h.pageType match
          case PageType.DICTIONARY_PAGE =>
            val body = Codecs.decompress(
              codec,
              file,
              bodyStart,
              h.compressedSize,
              h.uncompressedSize
            )
            page(Encoding.PLAIN, h.dictionaryPage.get.numValues, body, None)
          case PageType.DATA_PAGE =>
            val dp   = h.dataPage.get
            val body = Codecs.decompress(
              codec,
              file,
              bodyStart,
              h.compressedSize,
              h.uncompressedSize
            )
            var at = 0
            if leaf.maxRepetitionLevel > 0 then
              at += 4 + Bytes.intLE(body, at)
            var defs: Option[Array[Byte]] = None
            var n                         = dp.numValues
            if leaf.maxDefinitionLevel > 0 then
              val len = Bytes.intLE(body, at)
              val lv  = body.slice(at + 4, at + 4 + len)
              n = RleBitPackedDecoder(
                lv,
                0,
                lv.length,
                BitPacking.bitWidthOf(leaf.maxDefinitionLevel)
              ).readLevels(new Array[Byte](dp.numValues), 0, dp.numValues, leaf.maxDefinitionLevel)
              if leaf.maxRepetitionLevel == 0 then
                defs = Some(lv)
              at += 4 + len
            page(dp.encoding, n, body.drop(at), defs)
          case PageType.DATA_PAGE_V2 =>
            val dp     = h.dataPageV2.get
            val levels = dp.repetitionLevelsByteLength + dp.definitionLevelsByteLength
            val values =
              if dp.isCompressed then
                Codecs.decompress(
                  codec,
                  file,
                  bodyStart + levels,
                  h.compressedSize - levels,
                  h.uncompressedSize - levels
                )
              else
                file.slice(bodyStart + levels, bodyStart + h.compressedSize)
            page(dp.encoding, dp.numValues - dp.numNulls, values, None)
          case _ =>
        end match
    catch
      case _: ParquetException =>
    end try
    out.result()

  end chunkPages

  /** Encodings the value decoder target picks from with its first byte */
  val valueEncodings: IndexedSeq[Encoding] = IndexedSeq(
    Encoding.PLAIN,
    Encoding.RLE_DICTIONARY,
    Encoding.RLE,
    Encoding.DELTA_BINARY_PACKED,
    Encoding.DELTA_LENGTH_BYTE_ARRAY,
    Encoding.DELTA_BYTE_ARRAY,
    Encoding.BYTE_STREAM_SPLIT
  )

  /** A value decoder input: encoding, type, type length, value count, dictionary size, then data */
  def valueInput(p: Page, dictSize: Int): Array[Byte] =
    val enc = valueEncodings.indexOf(
      p.encoding match
        case Encoding.PLAIN_DICTIONARY =>
          Encoding.RLE_DICTIONARY
        case e =>
          e
    )
    Array[Byte](
      enc.toByte,
      p.tpe.id.toByte,
      p.typeLength.toByte,
      (p.numValues >>> 8).toByte,
      p.numValues.toByte,
      dictSize.toByte,
      0
    ) ++ p.values

  /** A level decoder input: bit width, max level, value count, mode, then RLE data */
  def levelInput(bitWidth: Int, maxLevel: Int, n: Int, mode: Int, data: Array[Byte]): Array[Byte] =
    Array[Byte](bitWidth.toByte, maxLevel.toByte, (n >>> 8).toByte, n.toByte, mode.toByte) ++ data

  private final class Mask(n: Int) extends NullMask:
    val array: Array[Boolean] = new Array[Boolean](n)

  /** A column type with a generator of its values, as readRows returns them */
  case class Typed(tpe: ParquetType, value: Gen[Any])

  private val leafTypes: Gen[Typed] = Gen.oneOf(
    Typed(ParquetType.BOOLEAN, Gen.oneOf(true, false)),
    Typed(ParquetType.INT32, Gen.chooseNum(Int.MinValue, Int.MaxValue)),
    Typed(ParquetType.INT64, Gen.chooseNum(Long.MinValue, Long.MaxValue)),
    Typed(ParquetType.DOUBLE, Gen.chooseNum(-1e12, 1e12)),
    Typed(
      ParquetType.STRING,
      Gen.oneOfGen(Gen.alphaNumStr, Gen.asciiStr, Gen.oneOf("", "é", "😀"))
    ),
    Typed(ParquetType.BINARY, Gen.listOf(Gen.chooseNum(-128, 127)).map(_.map(_.toByte).toArray))
  )

  private def optional(g: Gen[Any]): Gen[Any] = Gen.option(g).map(_.orNull)

  private val fieldName: Gen[String] = Gen.chooseNum(0, 999).map(i => s"c${i}")

  private def struct(t: Gen[Typed]): Gen[Typed] =
    for
      n      <- Gen.chooseNum(1, 3)
      fields <- Gen.listOfN(n, t.zip(Gen.oneOf(true, false)))
    yield
      val cols = fields
        .zipWithIndex
        .map { case ((f, opt), i) =>
          if opt then
            ParquetColumn.optional(s"f${i}", f.tpe)
          else
            ParquetColumn.required(s"f${i}", f.tpe)
        }
      val values = fields
        .zipWithIndex
        .map { case ((f, opt), i) =>
          (
            if opt then
              optional(f.value)
            else
              f.value
          ).map(v => s"f${i}" -> v)
        }
      Typed(
        ParquetType.struct(cols*),
        values
          .foldRight(Gen.const(List.empty[(String, Any)])) { (g, acc) =>
            g.zip(acc).map(_ :: _)
          }
          .map(_.toMap)
      )

  /** Primitives, and lists, maps, and structs of them nested up to 3 deep */
  val columnTypes: Gen[Typed] =
    Gen.recursive(leafTypes, maxDepth = 3) { t =>
      Gen.oneOfGen(
        t.zip(Gen.oneOf(true, false))
          .map { case (e, opt) =>
            Typed(
              ParquetType.list(e.tpe, opt),
              Gen.listOf(
                if opt then
                  optional(e.value)
                else
                  e.value
              )
            )
          },
        t.zip(Gen.oneOf(true, false))
          .map { case (v, opt) =>
            Typed(
              ParquetType.map(ParquetType.STRING, v.tpe, opt),
              Gen.mapOf(
                Gen.alphaNumStr,
                if opt then
                  optional(v.value)
                else
                  v.value
              )
            )
          },
        struct(t)
      )
    }

  case class Written(
      schema: ParquetSchema,
      rows: Seq[Map[String, Any]],
      options: ParquetWriteOptions
  )

  val writtenFiles: Gen[Written] =
    for
      cols <- Gen
        .chooseNum(1, 4)
        .flatMap(n => Gen.listOfN(n, columnTypes.zip(Gen.oneOf(true, false))))
      rows <- Gen.listOf(
        Gen.lzy {
          cols
            .zipWithIndex
            .foldRight(Gen.const(List.empty[(String, Any)])) { case (((t, opt), i), acc) =>
              (
                if opt then
                  optional(t.value)
                else
                  t.value
              ).zip(acc).map((v, rest) => (s"c${i}" -> v) :: rest)
            }
            .map(_.toMap)
        }
      )
      codec <- Gen.oneOf(writerCodecs)
      dict  <- Gen.oneOf(true, false)
      page  <- Gen.chooseNum(64, 4096)
    yield
      val schema = cols
        .zipWithIndex
        .foldLeft(ParquetSchema.builder("fuzz")) { case (b, ((t, opt), i)) =>
          if opt then
            b.optional(s"c${i}", t.tpe)
          else
            b.required(s"c${i}", t.tpe)
        }
        .build()
      Written(
        schema,
        rows,
        ParquetWriteOptions
          .default
          .withCompression(codec)
          .withDictionaryEnabled(dict)
          .withPageSize(page)
          .withDictionaryPageSize(page)
      )

  val writerCodecs: Seq[CompressionCodec] = Seq(
    CompressionCodec.UNCOMPRESSED,
    CompressionCodec.SNAPPY,
    CompressionCodec.ZSTD,
    CompressionCodec.LZ4_RAW,
    CompressionCodec.GZIP
  )

  /** Byte arrays compare by content */
  def normalize(v: Any): Any =
    v match
      case b: Array[Byte] =>
        b.toSeq
      case m: Map[?, ?] =>
        m.map((k, x) => normalize(k) -> normalize(x))
      case s: Iterable[?] =>
        s.map(normalize).toSeq
      case other =>
        other

  def u8(b: Array[Byte], i: Int): Int  = b(i) & 0xff
  def u16(b: Array[Byte], i: Int): Int = (u8(b, i) << 8) | u8(b, i + 1)

end ParquetFuzzTest

/**
  * Fuzz Uni's Parquet reader, which reads files from object stores and HTTP. A corrupt file must
  * fail with [[ParquetException]], never an index, cast, or allocation error.
  */
class ParquetFuzzTest extends UniTest:
  import ParquetFuzzTest.*
  FileSystemInit.init()

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(
      // The reader is shared Scala code; Native runs it in a slow debug build
      if isScalaNative then
        200
      else
        2000
    )
    .withCorpusDir("uni/src/test/resources/fuzz")
    .withMaxLength(8192)
    .withSlowInputMillis(2000)
    .withExpectedCases { case _: ParquetException =>
    }

  private val rows = (0 until 12).map { i =>
    Row(
      id = i * 1000003L,
      name = s"name-${i % 3}",
      score = Option.when(i % 4 != 0)(i * 0.5),
      flag = i  % 2 == 0,
      small = i % 5,
      tags = Seq.fill(i % 3)(s"t${i}"),
      counts = Map(s"k${i % 2}" -> i.toLong),
      inner = Option.when(i % 3 != 1)(Inner(i, Option.when(i % 2 == 0)("x")))
    )
  }

  private def written: Seq[Array[Byte]] =
    for
      codec <- writerCodecs
      dict  <- Seq(true, false)
    yield Parquet.writeBytes(
      rows,
      ParquetWriteOptions.default.withCompression(codec).withDictionaryEnabled(dict)
    )

  // Small files from other writers, for encodings and page layouts Uni's writer doesn't use
  private val fixtureNames = Seq(
    "apache/rle_boolean_encoding.parquet",
    "apache/datapage_v2_empty_datapage.snappy.parquet",
    "apache/nulls.snappy.parquet",
    "apache/int96_from_spark.parquet",
    "apache/null_list.parquet",
    "apache/fixed_length_decimal.parquet",
    "apache/hadoop_lz4_compressed.parquet",
    "apache/nested_lists.snappy.parquet",
    "apache/datapage_v2.snappy.parquet",
    "apache/nested_maps.snappy.parquet",
    "apache/concatenated_gzip_members.parquet",
    "apache/alltypes_dictionary.parquet",
    "apache/delta_length_byte_array.parquet",
    "duckdb/variant_array.parquet"
  )

  private def fixtures: Seq[Array[Byte]] = fixtureNames.map(TestFiles.bytes)

  private def readAll(bytes: Array[Byte]): Unit =
    val f = Parquet.open(bytes)
    try
      f.readRows().foreach(_ => ())
      f.readAs[Any]()(using AnyWeaver.default).foreach(_ => ())
    finally
      f.close()

  private lazy val pageSeeds: Seq[Page] = (
    written ++ fixtures ++
      Seq(
        "apache/delta_binary_packed.parquet",
        "apache/delta_byte_array.parquet",
        "apache/delta_encoding_optional_column.parquet",
        "apache/byte_stream_split.zstd.parquet"
      ).map(TestFiles.bytes)
  ).flatMap(pages).filter(_.values.length <= 32000)

  test("the seed files and pages read without errors") {
    // A seed that fails would only test the first check the reader makes
    (written ++ fixtures).foreach(readAll)
    val encodings = pageSeeds.map(_.encoding).toSet
    Seq(
      Encoding.PLAIN,
      Encoding.RLE_DICTIONARY,
      Encoding.DELTA_BINARY_PACKED,
      Encoding.DELTA_LENGTH_BYTE_ARRAY,
      Encoding.DELTA_BYTE_ARRAY,
      Encoding.BYTE_STREAM_SPLIT
    ).filterNot(encodings.contains) shouldBe Nil
    pageSeeds.exists(_.defLevels.nonEmpty) shouldBe true
  }

  test("the reader rejects corrupt files with ParquetException") {
    fuzz("parquet-read", fuzzConfig.withSeedBytes((written ++ fixtures)*).withDictionary("PAR1")) {
      bytes =>
        readAll(bytes)
    }
  }

  test("the reader rejects corrupt footers with ParquetException") {
    // The pages of a real file with the fuzzed bytes as its footer, so that mutations go into the
    // Thrift metadata and its offsets still point at pages
    val file   = written(2)
    val len    = Bytes.intLE(file, file.length - 8)
    val data   = file.take(file.length - 8 - len)
    val footer = file.slice(file.length - 8 - len, file.length - 8)
    fuzz("parquet-footer", fuzzConfig.withSeedBytes(footer)) { bytes =>
      val n = Array[Byte](
        bytes.length.toByte,
        (bytes.length >>> 8).toByte,
        (bytes.length >>> 16).toByte,
        0
      )
      readAll(data ++ bytes ++ n ++ "PAR1".getBytes("UTF-8"))
    }
  }

  test("page value decoders reject corrupt pages with ParquetException") {
    val seeds = pageSeeds
      .filter(p => valueEncodings.contains(p.encoding) || p.encoding == Encoding.PLAIN_DICTIONARY)
      .map(p => valueInput(p, 255))
    fuzz("parquet-values-decode", fuzzConfig.withMaxLength(32768).withSeedBytes(seeds*)) { bytes =>
      if bytes.length >= 7 then
        val enc        = valueEncodings(u8(bytes, 0) % valueEncodings.length)
        val tpe        = PhysicalType.of(u8(bytes, 1) % 8)
        val typeLength = u8(bytes, 2)
        val n          = u16(bytes, 3)
        val dictSize   = u8(bytes, 5)
        val asLong     = (bytes(6) & 1) != 0
        val dict       =
          Option.when(enc == Encoding.RLE_DICTIONARY) {
            // Zeros decode as a dictionary of zero values or empty strings
            val d = ValueDecoder.newBuffer(tpe, dictSize, asLong)
            val z = new Array[Byte](dictSize * math.max(12, typeLength))
            ValueDecoder.decodePlain(tpe, typeLength, z, 0, z.length, dictSize, d)
            d
          }
        val out = ValueDecoder.newBuffer(tpe, 16, asLong)
        ValueDecoder.decode(enc, tpe, typeLength, bytes, 7, bytes.length, n, out, dict)
        out.size shouldBe n
    }
  }

  test("RLE and bit-packed level decoders reject corrupt runs with ParquetException") {
    val seeds = pageSeeds.flatMap(p =>
      p.defLevels
        .toSeq
        .flatMap { lv =>
          val w = BitPacking.bitWidthOf(p.maxDef)
          Seq(0, 1, 2).map(mode => levelInput(w, p.maxDef, p.numValues, mode, lv))
        }
    )
    fuzz("parquet-levels-decode", fuzzConfig.withSeedBytes(seeds*)) { bytes =>
      if bytes.length >= 5 then
        val maxLevel = u8(bytes, 1) % 128
        val n        = u16(bytes, 2)
        u8(bytes, 4) % 3 match
          case 0 =>
            // Any bit width, including invalid ones
            RleBitPackedDecoder(bytes, 5, bytes.length, u8(bytes, 0) % 34).read(
              new Array[Int](n),
              0,
              n
            )
          case 1 =>
            // Readers derive the bit width from the max level, which readLevels relies on
            val d = RleBitPackedDecoder(bytes, 5, bytes.length, BitPacking.bitWidthOf(maxLevel))
            val levels = new Array[Byte](n)
            val count  = d.readLevels(levels, 0, n, maxLevel)
            (count <= n) shouldBe true
            levels.forall(l => l >= 0 && l <= maxLevel) shouldBe true
          case _ =>
            val d     = RleBitPackedDecoder(bytes, 5, bytes.length, u8(bytes, 0) % 34)
            val mask  = Mask(n)
            val count = d.readNulls(mask, 0, n)
            (count + mask.count) shouldBe n
            mask.release()
    }
  }

  test("written files read back the same rows under every codec") {
    // Writing and reading valid rows must not fail at all
    val config = fuzzConfig.withGenSize(16).withExpected(_ => false)
    fuzzGen("parquet-round-trip", writtenFiles, config) { w =>
      val out    = java.io.ByteArrayOutputStream()
      val writer = Parquet.newRecordWriter(out, w.schema, w.options)
      try writer.writeAll(w.rows)
      finally writer.close()
      val f = Parquet.open(out.toByteArray)
      try
        normalize(f.readRows().toSeq) shouldBe normalize(w.rows)
        // Weaver reads vectors through VectorUnpacker, which must give the values RowPacker packs
        val options = ParquetReadOptions.default
        f.readBatches()
          .foreach { batch =>
            val direct = ParquetFile.rows(batch, AnyWeaver.default, options).toSeq
            val packer = RowPacker(options.binaryAsString)
            val packed = (0 until batch.numRows).map { i =>
              val p = MessagePack.newPacker()
              packer.packRow(p, batch, i)
              val ctx = WeaverContext()
              AnyWeaver.default.unpack(MessagePack.newUnpacker(p.toByteArray), ctx)
              ctx.getLastValue
            }
            normalize(direct) shouldBe normalize(packed)
          }
      finally
        f.close()
    }
  }

end ParquetFuzzTest
