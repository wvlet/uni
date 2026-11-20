package example.parquetbench

import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.column.ColumnReader
import org.apache.parquet.column.impl.ColumnReadStoreImpl
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.io.LocalInputFile
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** parquet-java readers that do the same work as [[UniReaders]] */
object ParquetJavaReaders:

  /**
    * The column path: every leaf column read value by value with ColumnReader, the lowest-level
    * public API of parquet-column (parquet-java has no vectorized reader; Spark and Arrow ship
    * their own).
    */
  def columns(path: String): (Long, Long) =
    val reader = ParquetFileReader.open(LocalInputFile(Path.of(path)))
    try
      val schema    = reader.getFooter.getFileMetaData.getSchema
      val createdBy = reader.getFooter.getFileMetaData.getCreatedBy
      val columns   = schema.getColumns.asScala.toSeq
      var rows      = 0L
      var sum       = 0L
      var pages     = reader.readNextRowGroup()
      while pages != null do
        rows += pages.getRowCount
        val store = ColumnReadStoreImpl(
          pages,
          GroupRecordConverter(schema).getRootConverter,
          schema,
          createdBy
        )
        columns.foreach(c => sum += touch(store.getColumnReader(c), c))
        pages = reader.readNextRowGroup()
      (rows, sum)
    finally
      reader.close()

  @annotation.nowarn(
    "cat=deprecation"
  ) // getTotalValueCount: the simplest way to read a whole column
  private def touch(r: ColumnReader, c: ColumnDescriptor): Long =
    val n      = r.getTotalValueCount
    val maxDef = c.getMaxDefinitionLevel
    var s      = 0L
    var i      = 0L
    c.getPrimitiveType.getPrimitiveTypeName match
      case PrimitiveTypeName.INT32 =>
        while i < n do
          if r.getCurrentDefinitionLevel == maxDef then
            s += r.getInteger
          r.consume()
          i += 1
      case PrimitiveTypeName.INT64 =>
        while i < n do
          if r.getCurrentDefinitionLevel == maxDef then
            s += r.getLong
          r.consume()
          i += 1
      case PrimitiveTypeName.DOUBLE =>
        while i < n do
          if r.getCurrentDefinitionLevel == maxDef then
            s += r.getDouble.toLong
          r.consume()
          i += 1
      case PrimitiveTypeName.BINARY =>
        while i < n do
          if r.getCurrentDefinitionLevel == maxDef then
            val b = r.getBinary
            if b.length > 0 then
              s += b.toByteBuffer.get(0) + b.length
          r.consume()
          i += 1
      case other =>
        throw IllegalArgumentException(s"Unexpected type ${other} in the benchmark files")
    s

  end touch

  private def groupReader(path: String): ParquetReader[Group] =
    new ParquetReader.Builder[Group](LocalInputFile(Path.of(path))):
      override def getReadSupport(): ReadSupport[Group] = GroupReadSupport()
    .build()

  /**
    * Reads every field of a Group, building a String for each string value as a case class would,
    * so the row path does the same work as uni-parquet's case-class path
    */
  private def touchGroup(g: Group): Long =
    val t = g.getType
    var s = 0L
    var f = 0
    while f < t.getFieldCount do
      val ft = t.getType(f)
      val n  = g.getFieldRepetitionCount(f)
      var i  = 0
      while i < n do
        if ft.isPrimitive then
          ft.asPrimitiveType.getPrimitiveTypeName match
            case PrimitiveTypeName.INT32 =>
              s += g.getInteger(f, i)
            case PrimitiveTypeName.INT64 =>
              s += g.getLong(f, i)
            case PrimitiveTypeName.DOUBLE =>
              s += g.getDouble(f, i).toLong
            case PrimitiveTypeName.BINARY =>
              s += g.getString(f, i).length
            case other =>
              throw IllegalArgumentException(s"Unexpected type ${other} in the benchmark files")
        else
          s += touchGroup(g.getGroup(f, i))
        i += 1
      f += 1
    s

  /** The row path: the example Group reader, which builds one object tree per row */
  def rows(path: String): (Long, Long) =
    val r = groupReader(path)
    try
      var rows = 0L
      var sum  = 0L
      var g    = r.read()
      while g != null do
        rows += 1
        sum += touchGroup(g)
        g = r.read()
      (rows, sum)
    finally
      r.close()

end ParquetJavaReaders
