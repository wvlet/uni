package wvlet.uni.ai.bedrock

import wvlet.uni.rx.Rx

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
  * A frame of the AWS event-stream encoding (`application/vnd.amazon.eventstream`) that Bedrock
  * uses for ConverseStream and InvokeModelWithResponseStream. Only string headers are kept, which
  * is all Bedrock sends.
  */
case class EventStreamFrame(headers: Map[String, String], payload: Array[Byte]):
  def header(name: String): Option[String] = headers.get(name)
  def messageType: String                  = headers.getOrElse(":message-type", "event")
  def eventType: Option[String]            = headers.get(":event-type")
  def exceptionType: Option[String]        = headers.get(":exception-type")
  def payloadString: String                = String(payload, StandardCharsets.UTF_8)

object EventStreamFrame:
  /** Encode a frame, with the prelude and message CRCs */
  def encode(headers: Seq[(String, String)], payload: Array[Byte]): Array[Byte] =
    val h = ByteArrayOutputStream()
    headers.foreach { (name, value) =>
      val n = name.getBytes(StandardCharsets.UTF_8)
      val v = value.getBytes(StandardCharsets.UTF_8)
      h.write(n.length)
      h.write(n)
      h.write(7) // string
      h.write((v.length >> 8) & 0xff)
      h.write(v.length & 0xff)
      h.write(v)
    }
    val headerBytes         = h.toByteArray
    val total               = 12 + headerBytes.length + payload.length + 4
    val out                 = ByteArrayOutputStream(total)
    def int32(x: Int): Unit =
      out.write((x >>> 24) & 0xff)
      out.write((x >>> 16) & 0xff)
      out.write((x >>> 8) & 0xff)
      out.write(x & 0xff)
    int32(total)
    int32(headerBytes.length)
    int32(Crc32.of(out.toByteArray, 0, 8))
    out.write(headerBytes)
    out.write(payload)
    val body = out.toByteArray
    int32(Crc32.of(body, 0, body.length))
    out.toByteArray

  /** Encode an event frame (`:message-type event`) with a JSON payload */
  def event(eventType: String, json: String): Array[Byte] = encode(
    Seq(
      ":event-type"   -> eventType,
      ":content-type" -> "application/json",
      ":message-type" -> "event"
    ),
    json.getBytes(StandardCharsets.UTF_8)
  )

end EventStreamFrame

class EventStreamException(message: String) extends RuntimeException(message)

/**
  * Incremental decoder of event-stream frames. Feed it byte chunks as they arrive; it returns the
  * frames completed so far and keeps any partial frame for the next chunk.
  */
class EventStreamDecoder:
  private var buf: Array[Byte] = Array.emptyByteArray
  private var len              = 0

  def feed(chunk: Array[Byte]): Seq[EventStreamFrame] =
    ensure(len + chunk.length)
    System.arraycopy(chunk, 0, buf, len, chunk.length)
    len += chunk.length
    val frames = Seq.newBuilder[EventStreamFrame]
    var pos    = 0
    var more   = true
    while more && len - pos >= 12 do
      val total = int32(pos)
      if total < 16 then
        throw EventStreamException(s"Invalid event-stream frame length: ${total}")
      if len - pos >= total then
        frames += parse(pos, total)
        pos += total
      else
        more = false
    if pos > 0 then
      System.arraycopy(buf, pos, buf, 0, len - pos)
      len -= pos
    frames.result()

  /** Fail if bytes of an unfinished frame are left at the end of the stream */
  def finish(): Unit =
    if len > 0 then
      throw EventStreamException(s"Event stream ended inside a frame (${len} bytes left)")

  private def ensure(n: Int): Unit =
    if buf.length < n then
      val next = new Array[Byte](math.max(n, buf.length * 2))
      System.arraycopy(buf, 0, next, 0, len)
      buf = next

  private def int32(p: Int): Int =
    ((buf(p) & 0xff) << 24) |
      ((buf(p + 1) & 0xff) << 16) |
      ((buf(p + 2) & 0xff) << 8) |
      (buf(p + 3) & 0xff)

  private def int16(p: Int): Int = ((buf(p) & 0xff) << 8) | (buf(p + 1) & 0xff)

  private def parse(start: Int, total: Int): EventStreamFrame =
    val headersLen = int32(start + 4)
    if Crc32.of(buf, start, 8) != int32(start + 8) then
      throw EventStreamException("Event-stream prelude CRC mismatch")
    if Crc32.of(buf, start, total - 4) != int32(start + total - 4) then
      throw EventStreamException("Event-stream message CRC mismatch")
    val headers = Map.newBuilder[String, String]
    var p       = start + 12
    val hEnd    = p + headersLen
    while p < hEnd do
      val nameLen = buf(p) & 0xff
      val name    = String(buf, p + 1, nameLen, StandardCharsets.UTF_8)
      p += 1 + nameLen
      val tpe = buf(p) & 0xff
      p += 1
      tpe match
        case 0 | 1 =>
          headers += name -> (tpe == 0).toString
        case 2 =>
          p += 1
        case 3 =>
          p += 2
        case 4 =>
          p += 4
        case 5 | 8 =>
          p += 8
        case 6 =>
          p += 2 + int16(p)
        case 7 =>
          val l = int16(p)
          headers += name -> String(buf, p + 2, l, StandardCharsets.UTF_8)
          p += 2 + l
        case 9 =>
          p += 16
        case other =>
          throw EventStreamException(s"Unknown event-stream header type: ${other}")
      end match
    end while
    val payloadLen = total - 12 - headersLen - 4
    val payload    = new Array[Byte](payloadLen)
    System.arraycopy(buf, hEnd, payload, 0, payloadLen)
    EventStreamFrame(headers.result(), payload)

  end parse

end EventStreamDecoder

object EventStreamDecoder:
  /** Turn a stream of byte chunks into a stream of frames */
  def frames(bytes: Rx[Array[Byte]]): Rx[EventStreamFrame] =
    val decoder = EventStreamDecoder()
    bytes.flatMap(chunk => Rx.fromSeq(decoder.feed(chunk)))

/** CRC-32 (IEEE), as used by the event-stream prelude and message checksums */
private[bedrock] object Crc32:
  private val table: Array[Int] =
    Array.tabulate(256) { n =>
      var c = n
      for _ <- 0 until 8 do
        c =
          if (c & 1) != 0 then
            0xedb88320 ^ (c >>> 1)
          else
            c >>> 1
      c
    }

  def of(bytes: Array[Byte], offset: Int, length: Int): Int =
    var c = 0xffffffff
    var i = offset
    val e = offset + length
    while i < e do
      c = table((c ^ bytes(i)) & 0xff) ^ (c >>> 8)
      i += 1
    c ^ 0xffffffff
