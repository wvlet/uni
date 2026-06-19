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

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
  * Cross-platform RFC 6455 frame helpers: opcodes, the handshake accept key, and the (unmasked)
  * server-frame encoder. Shared by the Native and Node.js WebSocket backends so there is a single
  * framing implementation. Decoding of inbound (masked) client frames lives in
  * [[WebSocketFrameDecoder]].
  */
private[http] object WebSocketFrame:

  final val OpContinuation = 0x0
  final val OpText         = 0x1
  final val OpBinary       = 0x2
  final val OpClose        = 0x8
  final val OpPing         = 0x9
  final val OpPong         = 0xa

  private final val MagicGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** `Sec-WebSocket-Accept` = base64(SHA1(key + GUID)). */
  def acceptKey(key: String): String = Base64
    .getEncoder
    .encodeToString(Sha1.digest((key + MagicGuid).getBytes(StandardCharsets.US_ASCII)))

  /** Encode an unmasked server frame (FIN set). */
  def encodeFrame(opcode: Int, payload: Array[Byte]): Array[Byte] =
    val len    = payload.length
    val b0     = (0x80 | opcode).toByte
    val header =
      if len < 126 then
        Array[Byte](b0, len.toByte)
      else if len < 65536 then
        Array[Byte](b0, 126.toByte, ((len >>> 8) & 0xff).toByte, (len & 0xff).toByte)
      else
        val h = new Array[Byte](10)
        h(0) = b0
        h(1) = 127.toByte
        val l = len.toLong
        var i = 0
        while i < 8 do
          h(2 + i) = ((l >>> ((7 - i) * 8)) & 0xff).toByte
          i += 1
        h
    header ++ payload

  /** A Close-frame payload: a 2-byte big-endian status code followed by the UTF-8 reason. */
  def closePayload(statusCode: Int, reason: String): Array[Byte] =
    val reasonBytes = reason.getBytes(StandardCharsets.UTF_8)
    val payload     = new Array[Byte](2 + reasonBytes.length)
    payload(0) = ((statusCode >>> 8) & 0xff).toByte
    payload(1) = (statusCode & 0xff).toByte
    System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length)
    payload

end WebSocketFrame
