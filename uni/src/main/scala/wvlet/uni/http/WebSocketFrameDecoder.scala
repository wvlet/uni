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

import scala.collection.mutable

/**
  * A decoded inbound WebSocket event. Text/binary messages are delivered fully reassembled;
  * [[PeerClose]] and [[Fail]] are terminal (the decoder ignores further input after either).
  */
private[http] enum WsEvent:
  case Message(opcode: Int, data: Array[Byte])
  case Ping(data: Array[Byte])
  case Pong(data: Array[Byte])
  case PeerClose(code: Int, reason: String)
  case Fail(code: Int, reason: String)

/**
  * Incremental RFC 6455 frame decoder for inbound (masked, client->server) frames. Bytes are fed in
  * as they arrive — possibly splitting a frame across calls or batching several — and each complete
  * frame emits a [[WsEvent]]. A partial frame is buffered until the rest arrives; an incomplete
  * frame is never an error. Shared by the Native (blocking recv loop) and Node.js (socket `data`
  * events) backends.
  *
  * `maxFrameSize` bounds both a single frame and a reassembled fragmented message (close 1009).
  */
private[http] class WebSocketFrameDecoder(maxFrameSize: Int):
  import WebSocketFrame.*

  private val buf            = mutable.ArrayBuffer.empty[Byte]
  private val fragments      = mutable.ArrayBuffer.empty[Byte]
  private var fragmentOpcode = -1
  private var terminated     = false

  /**
    * Feed received bytes, invoking `emit` once per decoded event (in order). Returns false once a
    * terminal event ([[WsEvent.PeerClose]] or [[WsEvent.Fail]]) has been emitted — after which the
    * decoder ignores further input and the caller should stop reading.
    */
  def feed(bytes: Array[Byte])(emit: WsEvent => Unit): Boolean =
    if terminated then
      return false
    buf ++= bytes
    var progressed = true
    while progressed && !terminated do
      progressed = decodeOne(emit)
    !terminated

  /**
    * Decode one frame if fully buffered. Returns true if a frame was consumed (loop again), false
    * if more bytes are needed.
    */
  private def decodeOne(emit: WsEvent => Unit): Boolean =
    if buf.length < 2 then
      return false
    val b0        = buf(0) & 0xff
    val b1        = buf(1) & 0xff
    val fin       = (b0 & 0x80) != 0
    val rsv       = b0 & 0x70
    val opcode    = b0 & 0x0f
    val masked    = (b1 & 0x80) != 0
    val isControl = (opcode & 0x8) != 0
    val base      = b1 & 0x7f
    val headerLen =
      base match
        case 126 =>
          4
        case 127 =>
          10
        case _ =>
          2
    if buf.length < headerLen then
      return false

    var len = base
    if base == 126 then
      len = ((buf(2) & 0xff) << 8) | (buf(3) & 0xff)
    else if base == 127 then
      var l = 0L
      var i = 0
      while i < 8 do
        l = (l << 8) | (buf(2 + i) & 0xff).toLong
        i += 1
      len =
        if l > maxFrameSize then
          -1
        else
          l.toInt

    // Validation order matches the wire behavior: size, then RSV/mask, then control-frame rules.
    if len < 0 || len > maxFrameSize then
      return fail(emit, 1009, "message too big")
    if rsv != 0 || !masked then
      // RSV must be 0 (no extension negotiated); client frames MUST be masked (RFC 6455 §5).
      return fail(emit, 1002, "protocol error")
    if isControl && (!fin || len > 125) then
      // Control frames must be final and at most 125 bytes (RFC 6455 §5.5).
      return fail(emit, 1002, "invalid control frame")

    val total = headerLen + 4 + len // + 4-byte mask key
    if buf.length < total then
      return false

    val payload = new Array[Byte](len)
    val maskOff = headerLen
    val dataOff = headerLen + 4
    var i       = 0
    while i < len do
      payload(i) = (buf(dataOff + i) ^ buf(maskOff + (i % 4))).toByte
      i += 1
    buf.remove(0, total)

    dispatch(emit, fin, opcode, payload)
    true

  end decodeOne

  private def dispatch(
      emit: WsEvent => Unit,
      fin: Boolean,
      opcode: Int,
      payload: Array[Byte]
  ): Unit =
    opcode match
      case OpText | OpBinary =>
        if fragmentOpcode != -1 then
          fail(emit, 1002, "data frame during a fragmented message")
        else if fin then
          emit(WsEvent.Message(opcode, payload))
        else
          fragmentOpcode = opcode
          fragments.clear()
          fragments ++= payload
      case OpContinuation =>
        if fragmentOpcode == -1 then
          fail(emit, 1002, "continuation without a start frame")
        else
          fragments ++= payload
          if fragments.length > maxFrameSize then
            fail(emit, 1009, "message too big")
          else if fin then
            emit(WsEvent.Message(fragmentOpcode, fragments.toArray))
            fragments.clear()
            fragmentOpcode = -1
      case OpClose =>
        val code =
          if payload.length >= 2 then
            ((payload(0) & 0xff) << 8) | (payload(1) & 0xff)
          else
            1000
        emit(WsEvent.PeerClose(code, ""))
        terminated = true
      case OpPing =>
        emit(WsEvent.Ping(payload))
      case OpPong =>
        emit(WsEvent.Pong(payload))
      case _ =>
        fail(emit, 1002, s"unsupported opcode ${opcode}")

  /**
    * Emit a terminal failure and mark the decoder done. Always returns false (no further decoding).
    */
  private def fail(emit: WsEvent => Unit, code: Int, reason: String): Boolean =
    emit(WsEvent.Fail(code, reason))
    terminated = true
    false

end WebSocketFrameDecoder
