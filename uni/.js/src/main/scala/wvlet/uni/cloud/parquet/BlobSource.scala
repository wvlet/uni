package wvlet.uni.cloud.parquet

import org.scalajs.dom
import wvlet.uni.rx.Rx

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.Int8Array

/**
  * A Parquet file held in a browser `Blob` or `File`, read slice by slice, so opening a large file
  * reads only its footer.
  */
object BlobSource:
  def apply(blob: dom.Blob, name: String = "blob"): AsyncByteSource = BlobByteSource(blob, name)

private[parquet] final class BlobByteSource(blob: dom.Blob, val name: String)
    extends AsyncByteSource:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private def size: Long = blob.size.toLong

  private def slice(start: Long, end: Long): Rx[Array[Byte]] =
    val buf = blob.slice(start.toDouble, end.toDouble).arrayBuffer()
    Rx.future(buf.toFuture.map(b => toBytes(b)))

  private def toBytes(b: ArrayBuffer): Array[Byte] = js.typedarray.int8Array2ByteArray(Int8Array(b))

  def readAsync(offset: Long, length: Int): Rx[Array[Byte]] =
    if offset < 0 || length < 0 || offset + length > size then
      Rx.exception(
        ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")
      )
    else
      slice(offset, offset + length)

  def readTailAsync(length: Int): Rx[(Array[Byte], Long)] =
    val total = size
    val n     = math.min(total, length.toLong)
    slice(total - n, total).map(b => (b, total))
