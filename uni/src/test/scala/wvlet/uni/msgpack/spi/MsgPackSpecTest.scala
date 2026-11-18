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
package wvlet.uni.msgpack.spi

import wvlet.uni.msgpack.impl.ByteArrayBuffer
import wvlet.uni.test.UniTest

import java.math.BigInteger

/**
  * Replays the cases generated from the Lean model in `spec/lean/UniSpec/MsgPack.lean`: packs each
  * value with `OffsetPacker` and compares the exact bytes, then unpacks them with `OffsetUnpacker`
  * and checks it reads the value back and consumes every byte.
  */
class MsgPackSpecTest extends UniTest:

  private val buf = ByteArrayBuffer.newBuffer(16)

  private val longMin = BigInteger.valueOf(Long.MinValue)
  private val longMax = BigInteger.valueOf(Long.MaxValue)

  private def hex(len: Int): String = (0 until len)
    .map(i => f"${buf.readByte(i) & 0xff}%02x")
    .mkString

  // Pack, then return the written bytes in hex and a cursor for reading them back
  private def pack(body: WriteCursor => Unit): (String, ReadCursor) =
    val w = WriteCursor(buf, 0)
    body(w)
    (hex(w.lastWrittenBytes), ReadCursor(buf, 0))

  // The unpacked value and the number of bytes the unpacker consumed
  private def unpack[A](r: ReadCursor)(body: ReadCursor => A): (A, Int) =
    val v = body(r)
    (v, r.lastReadLength)

  private def check(kind: String, args: Seq[String], expected: String): Unit =
    val bytes = expected.length / 2
    kind match
      case "int" =>
        val v = BigInteger(args.head)
        if v.compareTo(longMin) >= 0 && v.compareTo(longMax) <= 0 then
          val (h, r) = pack(OffsetPacker.packLong(_, v.longValue))
          h shouldBe expected
          unpack(r)(OffsetUnpacker.unpackLong) shouldBe (v.longValue, bytes)
        else
          val (h, r) = pack(OffsetPacker.packBigInteger(_, v))
          h shouldBe expected
          unpack(r)(OffsetUnpacker.unpackBigInteger) shouldBe (v, bytes)
      case "str" =>
        val n      = args.head.toInt
        val (h, r) = pack(OffsetPacker.packRawStringHeader(_, n))
        h shouldBe expected
        unpack(r)(OffsetUnpacker.unpackRawStringHeader) shouldBe (n, bytes)
      case "bin" =>
        val n      = args.head.toInt
        val (h, r) = pack(OffsetPacker.packBinaryHeader(_, n))
        h shouldBe expected
        unpack(r)(OffsetUnpacker.unpackBinaryHeader) shouldBe (n, bytes)
      case "array" =>
        val n      = args.head.toInt
        val (h, r) = pack(OffsetPacker.packArrayHeader(_, n))
        h shouldBe expected
        unpack(r)(OffsetUnpacker.unpackArrayHeader) shouldBe (n, bytes)
      case "map" =>
        val n      = args.head.toInt
        val (h, r) = pack(OffsetPacker.packMapHeader(_, n))
        h shouldBe expected
        unpack(r)(OffsetUnpacker.unpackMapHeader) shouldBe (n, bytes)
      case "ext" =>
        val t      = args(0).toInt.toByte
        val n      = args(1).toInt
        val (h, r) = pack(OffsetPacker.packExtTypeHeader(_, t, n))
        h shouldBe expected
        unpack(r)(OffsetUnpacker.unpackExtTypeHeader) shouldBe (ExtTypeHeader(t, n), bytes)
      case other =>
        throw IllegalArgumentException(s"Unknown kind: ${other}")
    end match

  end check

  private val specCases: Seq[Seq[String]] = MsgPackSpecCases.cases.map(_.split(' ').toSeq)

  test("load the generated cases") {
    specCases.nonEmpty shouldBe true
  }

  specCases
    .groupBy(_.head)
    .toSeq
    .sortBy(_._1)
    .foreach { (kind, cases) =>
      test(s"${kind} encoding") {
        cases.foreach { c =>
          check(kind, c.tail.init, c.last)
        }
      }
    }

end MsgPackSpecTest
