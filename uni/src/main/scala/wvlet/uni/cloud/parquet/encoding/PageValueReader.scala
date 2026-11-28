package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.*
import wvlet.uni.cloud.parquet.format.Encoding
import wvlet.uni.cloud.parquet.format.PhysicalType

/**
  * Decodes the non-null values of one page in steps, each continuing where the last one stopped, so
  * a reader asking for small batches holds the decoded values of one batch instead of the whole
  * page. Only for the encodings [[PageValueReader.resumable]] accepts: PLAIN values of a fixed
  * width or byte arrays, and dictionary indices.
  */
private[parquet] final class PageValueReader(
    encoding: Encoding,
    tpe: PhysicalType,
    typeLength: Int,
    buf: Array[Byte],
    start: Int,
    end: Int,
    dictionary: Option[ValueBuffer]
):
  // The next value's bytes (PLAIN)
  private var pos = start
  // The dictionary indices (PLAIN_DICTIONARY, RLE_DICTIONARY), opened at the first read
  private var indexDecoder: RleBitPackedDecoder = null
  private val width                             = ValueDecoder.fixedWidth(tpe, typeLength)

  /**
    * Appends the next `n` values to `out`. `left` is the values left in the page, `n` included, for
    * sizing a binary buffer's data by this step's share of the page's bytes. `scratch` holds a
    * block of dictionary indices, as in [[ValueDecoder.decode]].
    */
  def read(n: Int, left: Int, out: ValueBuffer, scratch: Array[Int]): Unit =
    if n > 0 then
      encoding match
        case Encoding.PLAIN if width > 0 =>
          ValueDecoder.decodePlain(tpe, typeLength, buf, pos, end, n, out)
          pos += n * width
        case Encoding.PLAIN =>
          // BYTE_ARRAY: this step's share of the bytes left, less a 4-byte length per value
          val bytes = ((end - pos - 4L * left) * n / math.max(1, left)).toInt
          pos = ValueDecoder.decodePlainBinary(
            buf,
            pos,
            end,
            n,
            out.asInstanceOf[BinaryBuffer],
            bytes
          )
        case _ =>
          val dict = dictionary.getOrElse(
            throw ParquetException.corrupt("dictionary-encoded page without a dictionary page")
          )
          if indexDecoder == null then
            if start >= end then
              throw ParquetException.corrupt("dictionary index data is empty")
            indexDecoder = RleBitPackedDecoder(buf, start + 1, end, buf(start) & 0xff)
          indexDecoder.readDictionary(out, dict, n, scratch)

end PageValueReader

private[parquet] object PageValueReader:
  /** Whether a page of `encoding` values of `tpe` can be decoded in steps */
  def resumable(encoding: Encoding, tpe: PhysicalType): Boolean =
    encoding match
      case Encoding.PLAIN =>
        tpe != PhysicalType.BOOLEAN
      case Encoding.PLAIN_DICTIONARY | Encoding.RLE_DICTIONARY =>
        true
      case _ =>
        false
