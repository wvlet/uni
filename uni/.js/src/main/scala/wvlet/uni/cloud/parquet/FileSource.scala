package wvlet.uni.cloud.parquet

import wvlet.uni.http.NodeModules
import wvlet.uni.io.IOPath

import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

private[parquet] object FileSource:
  def open(path: IOPath): ByteSource =
    if !NodeModules.isNode then
      throw UnsupportedOperationException(
        "Local files need Node.js; in browsers, read a Blob or use ByteSource.ofUrlAsync"
      )
    NodeFileSource(path)

private[parquet] final class NodeFileSource(path: IOPath) extends ByteSource:
  private val fs = NodeModules.builtin("fs")
  private val fd = fs.openSync(path.posixPath, "r")

  def name: String = path.posixPath
  val size: Long   = fs.fstatSync(fd).size.asInstanceOf[Double].toLong

  def read(offset: Long, length: Int): Array[Byte] =
    if offset < 0 || length < 0 || offset + length > size then
      throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")
    val buf = Uint8Array(length)
    var pos = 0
    while pos < length do
      val n =
        fs.readSync(fd, buf, pos.toDouble, (length - pos).toDouble, (offset + pos).toDouble)
          .asInstanceOf[Double]
          .toInt
      if n <= 0 then
        throw ParquetException.corrupt(s"${name} ended before ${offset + length} bytes")
      pos += n
    js.typedarray.int8Array2ByteArray(Int8Array(buf.buffer, buf.byteOffset, length))

  override def close(): Unit = fs.closeSync(fd)
