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
package wvlet.uni.fuzz

import wvlet.uni.json.JSON
import wvlet.uni.json.JSONParseException
import wvlet.uni.msgpack.spi.MessageException
import wvlet.uni.msgpack.spi.JsonByteWriter
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.MsgPackJson
import wvlet.uni.test.UniTest
import wvlet.uni.test.fuzz.FuzzConfig

/**
  * Fuzz Uni's parsers: invalid input must be rejected with the parser's own exception, never an
  * index, cast, or allocation error. Saved failures live in `uni/src/test/resources/fuzz/<target>/`
  * and replay on every run.
  */
class ParserFuzzTest extends UniTest:

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(2000)
    .withCorpusDir("uni/src/test/resources/fuzz")

  private def msgpackSeeds: Seq[Array[Byte]] =
    def pack(f: wvlet.uni.msgpack.spi.BufferPacker => Unit): Array[Byte] =
      val p = MessagePack.newPacker()
      f(p)
      p.toByteArray
    Seq(
      pack(_.packInt(1).packLong(Long.MaxValue).packDouble(1.5).packNil.packBoolean(true)),
      pack(_.packString("hello").packString("x" * 40).packString("y" * 300)),
      pack { p =>
        p.packArrayHeader(3).packInt(-1).packString("a").packMapHeader(1)
        p.packString("k").packFloat(2.0f)
      },
      pack(_.packBinaryHeader(4).writePayload(Array[Byte](1, 2, 3, 4))),
      pack(_.packExtensionTypeHeader(1.toByte, 2).writePayload(Array[Byte](9, 9))),
      pack(_.packTimestamp(java.time.Instant.ofEpochSecond(1700000000L, 123)))
    )

  test("MessagePack unpacker rejects invalid input with MessageException") {
    fuzz(
      "msgpack-unpack",
      fuzzConfig
        .withSeedBytes(msgpackSeeds*)
        .withExpectedCases { case _: MessageException =>
        }
    ) { bytes =>
      val u = MessagePack.newUnpacker(bytes)
      while u.hasNext do
        u.unpackValue
    }
  }

  test("MessagePack to JSON conversion rejects invalid input with MessageException") {
    fuzz(
      "msgpack-to-json",
      fuzzConfig
        .withSeedBytes(msgpackSeeds*)
        .withExpectedCases { case _: MessageException =>
        }
    ) { bytes =>
      MsgPackJson.toJson(MessagePack.newUnpacker(bytes))
      JsonByteWriter().toJsonBytes(MessagePack.newUnpacker(bytes))
    }
  }

  test("JSON parser rejects invalid input with JSONParseException") {
    fuzzString(
      "json-parse",
      fuzzConfig
        .withSeeds(
          """{"a":1,"b":[true,false,null],"c":{"d":"eé\n"}}""",
          """[1.5e10,-0.0,123456789012345678901234567890,"😀"]""",
          "\"plain\"",
          "  [ ]  "
        )
        .withDictionary(
          "{",
          "}",
          "[",
          "]",
          ":",
          ",",
          "\"",
          "\\u",
          "true",
          "false",
          "null",
          "1e",
          "-"
        )
        .withExpectedCases { case _: JSONParseException =>
        }
    ) { s =>
      JSON.parseAny(s)
    }
  }

end ParserFuzzTest
