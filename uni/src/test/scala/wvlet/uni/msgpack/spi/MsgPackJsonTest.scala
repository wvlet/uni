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

import wvlet.uni.test.UniTest

import java.math.BigInteger
import java.time.Instant

class MsgPackJsonTest extends UniTest:

  /** Pack one value with `f`, then render it both ways */
  private def check(f: Packer => Unit): String =
    val p = MessagePack.newPacker()
    f(p)
    val bytes    = p.toByteArray
    val expected = MessagePack.newUnpacker(bytes).unpackValue.toJson
    MsgPackJson.toJson(MessagePack.newUnpacker(bytes)) shouldBe expected
    expected

  test("scalars render as their Value does") {
    check(_.packNil) shouldBe "null"
    check(_.packBoolean(true))
    check(_.packBoolean(false))
    Seq(
      0L,
      1L,
      -1L,
      9L,
      10L,
      99L,
      100L,
      -10L,
      127L,
      -33L,
      65536L,
      1000000L,
      Long.MaxValue,
      Long.MinValue,
      Long.MinValue + 1
    ).foreach(v => check(_.packLong(v)))
    check(_.packBigInteger(BigInteger("18446744073709551615")))
    Seq(0.0, -1.5, 1e300, Double.NaN, Double.PositiveInfinity, Double.MinPositiveValue).foreach(v =>
      check(_.packDouble(v))
    )
    check(_.packFloat(3.14f))
  }

  test("strings escape as their Value does") {
    Seq(
      "",
      "plain",
      "quote \" and backslash \\",
      "control \n\r\t\b\f \u0001 \u001f",
      "unicode café 日本語 🎉",
      "lone surrogate \ud800 end",
      "a" * 300
    ).foreach(s => check(_.packString(s)))
  }

  test("binary, timestamps, and extensions render as their Value does") {
    check { p =>
      p.packBinaryHeader(4)
      p.writePayload(Array[Byte](0, 1, -2, 127))
    }
    check(_.packTimestamp(Instant.parse("2026-11-19T12:34:56.789Z")))
    check { p =>
      p.packExtensionTypeHeader(5.toByte, 3)
      p.writePayload(Array[Byte](1, 2, 3))
    }
  }

  test("arrays and maps keep their order, with any key type") {
    check { p =>
      p.packMapHeader(7)
      Seq("z", "a", "m", "b", "y", "c", "x")
        .zipWithIndex
        .foreach { (k, i) =>
          p.packString(k)
          p.packInt(i)
        }
    } shouldBe """{"z":0,"a":1,"m":2,"b":3,"y":4,"c":5,"x":6}"""
    check { p =>
      p.packMapHeader(3)
      p.packInt(1)
      p.packString("int key")
      p.packBoolean(true)
      p.packNil
      p.packArrayHeader(2)
      p.packInt(1)
      p.packString("q\"")
      p.packArrayHeader(0)
    }
    check { p =>
      p.packArrayHeader(3)
      p.packMapHeader(0)
      p.packArrayHeader(1)
      p.packMapHeader(1)
      p.packString("k")
      p.packArrayHeader(2)
      p.packNil
      p.packDouble(2.5)
      p.packString("end")
    }
  }

end MsgPackJsonTest
