package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.IntBuffer
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.test.UniTest

class EncodingTest extends UniTest:

  test("decode RLE and bit-packed runs") {
    // RLE run of 3 x 5 (bit width 3), then one bit-packed group of 8 values 0..7
    val data = Array[Byte](6, 5, 3, 0x88.toByte, 0xc6.toByte, 0xfa.toByte)
    val out  = new Array[Int](11)
    RleBitPackedDecoder(data, 0, data.length, 3).read(out, 0, 11)
    out.toSeq shouldBe Seq(5, 5, 5, 0, 1, 2, 3, 4, 5, 6, 7)
  }

  test("reject a bit-packed run longer than its data") {
    // Header for 2^20 groups of width 8, with only 2 data bytes
    val data = Array[Byte](0x81.toByte, 0x80.toByte, 0x80.toByte, 0x01, 1, 2)
    intercept[ParquetException] {
      RleBitPackedDecoder(data, 0, data.length, 8).read(new Array[Int](8), 0, 8)
    }
  }

  test("decode zero-width bit-packed runs without unpacking") {
    // 2^24 groups of width 0: all zeros
    val data = Array[Byte](0x81.toByte, 0x80.toByte, 0x80.toByte, 0x10)
    val out  = Array.fill(16)(9)
    RleBitPackedDecoder(data, 0, data.length, 0).read(out, 0, 16)
    out.forall(_ == 0) shouldBe true
  }

  test("reject a DELTA_BINARY_PACKED header with a huge block size") {
    // block size 2^30 (varint), 1 miniblock, 1 value, first value 0
    val data = Array[Byte](0x80.toByte, 0x80.toByte, 0x80.toByte, 0x80.toByte, 0x04, 1, 1, 0)
    intercept[ParquetException] {
      DeltaDecoder.decodeBinaryPacked(PhysicalType.INT32, data, 0, data.length, 1, IntBuffer())
    }
  }

end EncodingTest
