package wvlet.uni.arrow

import wvlet.uni.arrow.ArrowTestReader.*
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.parquet.ParquetReadOptions

/**
  * Writes Arrow samples, with the rows the test reader decodes from them as JSON, for checking that
  * pyarrow reads the same values. The JVM CI job runs:
  *
  * {{{
  * ./sbt "uniJVM/Test/runMain wvlet.uni.arrow.WriteArrowSamples target/arrow-samples"
  * uv run --with pyarrow python uni/src/test/resources/arrow/check_with_pyarrow.py target/arrow-samples
  * }}}
  */
object WriteArrowSamples:
  import ArrowSamples.*

  def main(args: Array[String]): Unit =
    FileSystemInit.init()
    val dir = IOPath.parse(args.headOption.getOrElse("target/arrow-samples"))
    FileSystem.createDirectory(dir)
    def batches() = parquet.readBatches(ParquetReadOptions.default.withBatchSize(128)).toSeq
    def write(name: String, bytes: Array[Byte], file: Boolean): Unit =
      FileSystem.writeBytes(dir / name, bytes)
      val r =
        if file then
          readFile(bytes)
        else
          readStream(bytes)
      FileSystem.writeString(dir / s"${name}.json", rowsJson(r.rows))

    val zstd = ArrowWriteOptions.default.withCompression(ArrowCompression.ZSTD)
    val lz4  = ArrowWriteOptions.default.withCompression(ArrowCompression.LZ4_FRAME)
    write("rows.arrows", Arrow.toStreamBytes(parquet.schema, batches()), file = false)
    write("rows.arrow", Arrow.toFileBytes(parquet.schema, batches()), file = true)
    write("rows-zstd.arrows", Arrow.toStreamBytes(parquet.schema, batches(), zstd), file = false)
    write("rows-lz4.arrow", Arrow.toFileBytes(parquet.schema, batches(), lz4), file = true)
    write("types.arrows", Arrow.toStreamBytes(typesSchema, Seq(typesBatch)), file = false)
    write("empty.arrows", Arrow.toStreamBytes(parquet.schema, Seq.empty), file = false)
    println(s"Wrote Arrow samples to ${dir}")

  private def rowsJson(rows: Seq[Map[String, Any]]): String = rows
    .map(r => toJson(r))
    .mkString("[\n", ",\n", "\n]\n")

  /**
    * Values in the form check_with_pyarrow.py normalizes pyarrow values to: integers (also
    * decimals, as unscaled values, and temporal types) as strings, binaries as `0x` hex
    */
  private def toJson(v: Any): String =
    v match
      case null =>
        "null"
      case b: Boolean =>
        b.toString
      case i: BigInt =>
        s"\"${i}\""
      case f: Float =>
        if f.isNaN || f.isInfinite then
          s"\"${f}\""
        else
          f.toDouble.toString
      case d: Double =>
        d.toString
      case s: String =>
        s"\"${JSON.quoteJSONString(s)}\""
      case Hex(h) =>
        s"\"0x${h}\""
      case MonthDayNano(m, d, ns) =>
        s"{\"months\":${m},\"days\":${d},\"nanoseconds\":${ns}}"
      case m: Map[?, ?] =>
        m.toSeq
          .sortBy(_._1.toString)
          .map((k, x) => s"\"${JSON.quoteJSONString(k.toString)}\":${toJson(x)}")
          .mkString("{", ",", "}")
      case s: Seq[?] =>
        s.map(toJson).mkString("[", ",", "]")
      case other =>
        throw IllegalArgumentException(s"No JSON form for ${other}")

end WriteArrowSamples
