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
package wvlet.uni.http

import wvlet.uni.test.UniTest

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Cross-platform unit tests for the RFC 6455 frame decoder. These lock the framing contract that
  * the Native and Node.js WebSocket backends both depend on.
  */
class WebSocketFrameDecoderTest extends UniTest:

  private val mask = Array[Byte](0x12, 0x34, 0x56, 0x78)

  /** Build a masked client frame (FIN configurable) for `opcode` + `payload`. */
  private def frame(opcode: Int, payload: Array[Byte], fin: Boolean = true): Array[Byte] =
    val b0 = (
      (
        if fin then
          0x80
        else
          0x00
      ) | opcode
    ).toByte
    val len    = payload.length
    val header =
      if len < 126 then
        Array[Byte](b0, (0x80 | len).toByte)
      else if len < 65536 then
        Array[Byte](b0, (0x80 | 126).toByte, ((len >>> 8) & 0xff).toByte, (len & 0xff).toByte)
      else
        val h = new Array[Byte](10)
        h(0) = b0
        h(1) = (0x80 | 127).toByte
        val l = len.toLong
        var i = 0
        while i < 8 do
          h(2 + i) = ((l >>> ((7 - i) * 8)) & 0xff).toByte
          i += 1
        h
    val masked = new Array[Byte](len)
    var i      = 0
    while i < len do
      masked(i) = (payload(i) ^ mask(i % 4)).toByte
      i += 1
    header ++ mask ++ masked

  end frame

  private def textFrame(s: String, fin: Boolean = true): Array[Byte] = frame(
    WebSocketFrame.OpText,
    s.getBytes(StandardCharsets.UTF_8),
    fin
  )

  /** Feed all bytes at once and collect emitted events. */
  private def decode(bytes: Array[Byte], maxFrameSize: Int = 1024 * 1024): Seq[WsEvent] =
    val events  = mutable.ArrayBuffer.empty[WsEvent]
    val decoder = WebSocketFrameDecoder(maxFrameSize)
    decoder.feed(bytes)(events += _)
    events.toSeq

  test("decode a single masked text frame") {
    decode(textFrame("hello")) shouldMatch {
      case Seq(WsEvent.Message(WebSocketFrame.OpText, data)) =>
        new String(data, StandardCharsets.UTF_8) shouldBe "hello"
    }
  }

  test("decode a binary frame") {
    val payload = Array[Byte](1, 2, 3, 4, 5)
    decode(frame(WebSocketFrame.OpBinary, payload)) shouldMatch {
      case Seq(WsEvent.Message(WebSocketFrame.OpBinary, data)) =>
        data.toSeq shouldBe payload.toSeq
    }
  }

  test("decode multiple frames in one feed") {
    decode(textFrame("a") ++ textFrame("b")) shouldMatch {
      case Seq(WsEvent.Message(_, d1), WsEvent.Message(_, d2)) =>
        new String(d1, StandardCharsets.UTF_8) shouldBe "a"
        new String(d2, StandardCharsets.UTF_8) shouldBe "b"
    }
  }

  test("reassemble a fragmented text message") {
    val start = textFrame("Hel", fin = false)
    val cont  = frame(
      WebSocketFrame.OpContinuation,
      "lo".getBytes(StandardCharsets.UTF_8),
      fin = true
    )
    decode(start ++ cont) shouldMatch { case Seq(WsEvent.Message(WebSocketFrame.OpText, data)) =>
      new String(data, StandardCharsets.UTF_8) shouldBe "Hello"
    }
  }

  test("a new data frame during fragmentation fails with 1002") {
    val start = textFrame("Hel", fin = false)
    decode(start ++ textFrame("oops")) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("a continuation without a start frame fails with 1002") {
    val cont = frame(WebSocketFrame.OpContinuation, "x".getBytes(StandardCharsets.UTF_8))
    decode(cont) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("a ping emits Ping and a pong is emitted") {
    decode(frame(WebSocketFrame.OpPing, "p".getBytes(StandardCharsets.UTF_8))) shouldMatch {
      case Seq(WsEvent.Ping(data)) =>
        new String(data, StandardCharsets.UTF_8) shouldBe "p"
    }
    decode(frame(WebSocketFrame.OpPong, Array.emptyByteArray)) shouldMatch {
      case Seq(WsEvent.Pong(_)) =>
    }
  }

  test("a close frame emits PeerClose with the peer's code") {
    val payload = Array[Byte](((1001 >>> 8) & 0xff).toByte, (1001 & 0xff).toByte)
    decode(frame(WebSocketFrame.OpClose, payload)) shouldMatch {
      case Seq(WsEvent.PeerClose(1001, _)) =>
    }
  }

  test("an unmasked frame fails with 1002") {
    // Hand-build an unmasked text frame (mask bit clear, no mask key).
    val payload  = "hi".getBytes(StandardCharsets.UTF_8)
    val unmasked = Array[Byte](0x81.toByte, payload.length.toByte) ++ payload
    decode(unmasked) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("a set RSV bit fails with 1002") {
    val f = textFrame("x")
    f(0) = (f(0) | 0x40).toByte // set RSV1
    decode(f) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("an oversized frame fails with 1009") {
    decode(textFrame("abcdef"), maxFrameSize = 3) shouldMatch { case Seq(WsEvent.Fail(1009, _)) =>
    }
  }

  test("an oversized reassembled message fails with 1009") {
    val start = textFrame("aa", fin = false)
    val cont  = frame(
      WebSocketFrame.OpContinuation,
      "bb".getBytes(StandardCharsets.UTF_8),
      fin = true
    )
    // Each frame (2 bytes) is within the cap, but the reassembled message (4) exceeds it.
    decode(start ++ cont, maxFrameSize = 3) shouldMatch { case Seq(WsEvent.Fail(1009, _)) =>
    }
  }

  test("a fragmented (non-final) control frame fails with 1002") {
    val ping = frame(WebSocketFrame.OpPing, "x".getBytes(StandardCharsets.UTF_8), fin = false)
    decode(ping) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("an oversized control frame fails with 1002") {
    val big = frame(WebSocketFrame.OpPing, new Array[Byte](126))
    decode(big) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("an 8-byte length with the high bit set fails with 1009") {
    // FIN+text, masked, 127 extended length whose MSB is set (a negative signed Long).
    val frame = Array[Byte](0x81.toByte, 0xff.toByte, 0xff.toByte, 0, 0, 0, 0, 0, 0, 1)
    decode(frame) shouldMatch { case Seq(WsEvent.Fail(1009, _)) =>
    }
  }

  test("a stream split one byte per feed decodes identically") {
    val stream  = textFrame("hello") ++ frame(WebSocketFrame.OpBinary, Array[Byte](9, 8, 7))
    val events  = mutable.ArrayBuffer.empty[WsEvent]
    val decoder = WebSocketFrameDecoder(1024 * 1024)
    var i       = 0
    while i < stream.length do
      decoder.feed(Array(stream(i)))(events += _)
      i += 1
    events.toSeq shouldMatch {
      case Seq(
            WsEvent.Message(WebSocketFrame.OpText, d1),
            WsEvent.Message(WebSocketFrame.OpBinary, d2)
          ) =>
        new String(d1, StandardCharsets.UTF_8) shouldBe "hello"
        d2.toSeq shouldBe Seq[Byte](9, 8, 7)
    }
  }

  /** Build an unmasked server frame (for client-mode decoding). */
  private def serverFrame(opcode: Int, payload: Array[Byte]): Array[Byte] =
    val len    = payload.length
    val header =
      if len < 126 then
        Array[Byte]((0x80 | opcode).toByte, len.toByte)
      else
        Array[Byte](
          (0x80 | opcode).toByte,
          126.toByte,
          ((len >>> 8) & 0xff).toByte,
          (len & 0xff).toByte
        )
    header ++ payload

  test("client mode decodes an unmasked server text frame") {
    val events  = mutable.ArrayBuffer.empty[WsEvent]
    val decoder = WebSocketFrameDecoder(1024 * 1024, expectMasked = false)
    decoder.feed(serverFrame(WebSocketFrame.OpText, "hi".getBytes(StandardCharsets.UTF_8)))(
      events += _
    )
    events.toSeq shouldMatch { case Seq(WsEvent.Message(WebSocketFrame.OpText, data)) =>
      new String(data, StandardCharsets.UTF_8) shouldBe "hi"
    }
  }

  test("client mode rejects a masked frame with 1002") {
    val events  = mutable.ArrayBuffer.empty[WsEvent]
    val decoder = WebSocketFrameDecoder(1024 * 1024, expectMasked = false)
    // A masked text frame (what a server must never send) — the client must reject it.
    decoder.feed(textFrame("nope"))(events += _)
    events.toSeq shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
  }

  test("input after a terminal event is ignored") {
    val decoder = WebSocketFrameDecoder(1024 * 1024)
    val events  = mutable.ArrayBuffer.empty[WsEvent]
    decoder.feed(frame(WebSocketFrame.OpClose, Array.emptyByteArray))(events += _) shouldBe false
    // Further frames are dropped.
    decoder.feed(textFrame("ignored"))(events += _) shouldBe false
    events.size shouldBe 1
  }

  test("fail a text message with invalid UTF-8 (1007), as Netty's Utf8FrameValidator does") {
    Seq(
      Array(0xc3.toByte),                                       // truncated sequence
      Array(0xc0.toByte, 0xaf.toByte),                          // overlong '/'
      Array(0xed.toByte, 0xa0.toByte, 0x80.toByte),             // a UTF-16 surrogate
      Array(0xf4.toByte, 0x90.toByte, 0x80.toByte, 0x80.toByte) // above U+10FFFF
    ).foreach { bytes =>
      decode(frame(WebSocketFrame.OpText, bytes)) shouldMatch { case Seq(WsEvent.Fail(1007, _)) =>
      }
    }
    // A code point split across fragments is valid once reassembled
    val euro = "€".getBytes(StandardCharsets.UTF_8)
    decode(
      frame(WebSocketFrame.OpText, euro.take(1), fin = false) ++
        frame(WebSocketFrame.OpContinuation, euro.drop(1))
    ) shouldMatch { case Seq(WsEvent.Message(WebSocketFrame.OpText, data)) =>
      new String(data, StandardCharsets.UTF_8) shouldBe "€"
    }
    // Binary messages are not checked
    decode(frame(WebSocketFrame.OpBinary, Array(0xff.toByte))) shouldMatch {
      case Seq(WsEvent.Message(WebSocketFrame.OpBinary, _)) =>
    }
  }

  test("check close frames: status code and UTF-8 reason") {
    def close(payload: Array[Byte]): Seq[WsEvent] = decode(frame(WebSocketFrame.OpClose, payload))
    close(Array.emptyByteArray) shouldMatch { case Seq(WsEvent.PeerClose(1000, "")) =>
    }
    close(Array[Byte](0x03, 0xe8.toByte) ++ "bye".getBytes(StandardCharsets.UTF_8)) shouldMatch {
      case Seq(WsEvent.PeerClose(1000, "bye")) =>
    }
    close(Array[Byte](0x03)) shouldMatch { case Seq(WsEvent.Fail(1002, _)) =>
    }
    // 1005 and 1006 are never sent on the wire, and 999 is outside every range
    Seq(1005, 1006, 999, 2000, 5000).foreach { code =>
      close(Array((code >> 8).toByte, (code & 0xff).toByte)) shouldMatch {
        case Seq(WsEvent.Fail(1002, _)) =>
      }
    }
    close(Array[Byte](0x0f, 0xa0.toByte) ++ Array(0xff.toByte)) shouldMatch {
      case Seq(WsEvent.Fail(1007, _)) =>
    }
  }

end WebSocketFrameDecoderTest
