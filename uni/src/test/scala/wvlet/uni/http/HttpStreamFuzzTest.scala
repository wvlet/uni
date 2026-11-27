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
import wvlet.uni.test.fuzz.FuzzConfig

import java.nio.charset.StandardCharsets

/**
  * Fuzz the incremental decoders of WebSocket frames and server-sent events. Bytes reach them in
  * reads of any size, so how a stream is split must never change what they decode, and no input may
  * make them throw anything but their own errors.
  */
class HttpStreamFuzzTest extends UniTest:

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(
      if isScalaNative then
        300
      else
        2000
    )
    .withCorpusDir("uni/src/test/resources/fuzz")

  // Whole, one byte per read, and two uneven sizes
  private val chunkSizes = Seq(Int.MaxValue, 1, 3, 7)

  private def chunks(bytes: Array[Byte], size: Int): Iterator[Array[Byte]] =
    if bytes.isEmpty then
      Iterator.empty
    else
      bytes.grouped(math.min(size, bytes.length))

  private def splitInvariant[A](bytes: Array[Byte])(decode: Iterator[Array[Byte]] => A): Unit =
    val whole = decode(chunks(bytes, Int.MaxValue))
    chunkSizes
      .tail
      .foreach { size =>
        val split = decode(chunks(bytes, size))
        if split != whole then
          fail(s"reads of ${size} bytes give ${split}, but one read gives ${whole}")
      }

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  private def wsEvents(expectMasked: Boolean)(in: Iterator[Array[Byte]]): Seq[String] =
    val dec = WebSocketFrameDecoder(maxFrameSize = 64, expectMasked = expectMasked)
    val out = Seq.newBuilder[String]
    in.takeWhile(c =>
        dec.feed(c) {
          case WsEvent.Message(op, data) =>
            out += s"message ${op} ${hex(data)}"
          case WsEvent.Ping(data) =>
            out += s"ping ${hex(data)}"
          case WsEvent.Pong(data) =>
            out += s"pong ${hex(data)}"
          case WsEvent.PeerClose(code, reason) =>
            out += s"close ${code} ${reason}"
          case WsEvent.Fail(code, reason) =>
            out += s"fail ${code} ${reason}"
        }
      )
      .foreach(_ => ())
    out.result()

  private val mask = Array[Byte](1, 2, 3, 4)

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def wsSeeds(masked: Boolean): Seq[Array[Byte]] =
    def frame(op: Int, payload: Array[Byte], fin: Boolean = true): Array[Byte] =
      val f =
        if masked then
          WebSocketFrame.encodeMaskedFrame(op, payload, mask)
        else
          WebSocketFrame.encodeFrame(op, payload)
      if !fin then
        f(0) = (f(0) & 0x7f).toByte
      f
    Seq(
      frame(1, utf8("hello é")),
      frame(2, Array[Byte](0, 1, 2, -1)) ++ frame(9, utf8("ping")) ++ frame(10, Array.empty),
      frame(1, utf8("frag"), fin = false) ++ frame(9, Array.empty) ++ frame(0, utf8("ment")),
      frame(8, WebSocketFrame.closePayload(1000, "bye")),
      frame(2, Array.fill(70)(7.toByte))
    )

  for masked <- Seq(true, false) do
    val target =
      if masked then
        "websocket-decode-masked"
      else
        "websocket-decode"
    test(s"WebSocket frames decode the same for every read size (masked: ${masked})") {
      fuzz(target, fuzzConfig.withSeedBytes(wsSeeds(masked)*)) { bytes =>
        splitInvariant(bytes)(wsEvents(masked))
      }
    }

  private def sseEvents(in: Iterator[Array[Byte]]): Either[String, Seq[ServerSentEvent]] =
    val p = ServerSentEventParser(maxLineLength = 32, maxEventSize = 64)
    try
      val events = in.flatMap(p.feed).toList
      Right(events ++ p.flush())
    catch
      case e: ServerSentEventParser.EventTooLargeException =>
        Left("too large")

  test("server-sent events parse the same for every read size") {
    fuzz(
      "sse-parse",
      fuzzConfig
        .withSeeds(
          "data: hello\n\n",
          "id: 1\nevent: update\nretry: 300\ndata: a\ndata: b\r\n\r\n",
          ": comment\ndata:é😀\n\ndata\n\n",
          "data: unterminated"
        )
        .withDictionary("data:", "id:", "event:", "retry:", ": ", "\n", "\r\n", "\n\n", "é")
    ) { bytes =>
      splitInvariant(bytes)(sseEvents)
    }
  }

end HttpStreamFuzzTest
