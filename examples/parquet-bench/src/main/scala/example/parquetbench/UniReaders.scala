package example.parquetbench

import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.Parquet

/**
  * Uni readers. Each returns (rows, checksum); the checksum keeps the JIT from skipping
  * work.
  */
object UniReaders:

  /** Reads every column into vectors and touches each value */
  def columns(path: String): (Long, Long) =
    val f = Parquet.open(path)
    try
      var rows = 0L
      var sum  = 0L
      f.readBatches()
        .foreach { b =>
          rows += b.numRows
          b.columns.foreach(c => sum += touch(c))
        }
      (rows, sum)
    finally
      f.close()

  private def touch(c: ColumnVector): Long =
    c match
      case v: BooleanColumn =>
        var s = 0L
        var i = 0
        while i < v.size do
          if v.values(i) then
            s += 1
          i += 1
        s
      case v: IntColumn =>
        var s = 0L
        var i = 0
        while i < v.size do
          s += v.values(i)
          i += 1
        s
      case v: LongColumn =>
        var s = 0L
        var i = 0
        while i < v.size do
          s += v.values(i)
          i += 1
        s
      case v: FloatColumn =>
        var s = 0L
        var i = 0
        while i < v.size do
          s += v.values(i).toLong
          i += 1
        s
      case v: DoubleColumn =>
        var s = 0L
        var i = 0
        while i < v.size do
          s += v.values(i).toLong
          i += 1
        s
      case v: BinaryColumn =>
        // Touch the bytes, as a consumer of the strings would
        var s = 0L
        var i = 0
        while i < v.size do
          val len = v.length(i)
          if len > 0 then
            s += v.data(v.offsets(i)) + len
          i += 1
        s
      case v: ListColumn =>
        v.offsets(v.offsets.length - 1) + touch(v.elements)
      case v: MapColumn =>
        v.offsets(v.offsets.length - 1) + touch(v.keys) + touch(v.values)
      case v: StructColumn =>
        v.children.map(touch).sum

  def lineItems(path: String): (Long, Long) =
    val f = Parquet.open(path)
    try
      var rows = 0L
      var sum  = 0L
      f.readAs[LineItem]()
        .foreach { r =>
          rows += 1
          sum += r.lOrderkey + r.lComment.length
        }
      (rows, sum)
    finally
      f.close()

  def orders(path: String): (Long, Long) =
    val f = Parquet.open(path)
    try
      var rows = 0L
      var sum  = 0L
      f.readAs[Order]()
        .foreach { r =>
          rows += 1
          sum += r.oOrderkey + r.lines.size + r.attrs.map(_.size).getOrElse(0)
        }
      (rows, sum)
    finally
      f.close()

end UniReaders
