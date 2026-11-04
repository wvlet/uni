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
package wvlet.uni.cli.term

/**
  * The numeric parameters of a CSI sequence. A missing parameter (as in `CSI ;5H`) reads as the
  * given default. Parameters separated by `:` (as in `CSI 38:2::255:0:0m`) are sub-parameters of
  * the parameter before them.
  */
class CsiParams private[term] ():
  private[term] val values: Array[Int]    = Array.fill(EscapeParser.MaxParams)(-1)
  private[term] val isSub: Array[Boolean] = Array.fill(EscapeParser.MaxParams)(false)
  private[term] var count: Int            = 0
  private[term] def clear(): Unit         =
    var i = 0
    while i < count do
      values(i) = -1
      isSub(i) = false
      i += 1
    count = 0

  /**
    * The number of parameters, including sub-parameters
    */
  def size: Int = count

  /**
    * The i-th parameter, or `default` when it is missing or zero-length
    */
  def apply(i: Int, default: Int = 0): Int =
    if i < count && values(i) >= 0 then
      values(i)
    else
      default

  /**
    * True when the i-th parameter was written after a `:`
    */
  def isSubParam(i: Int): Boolean = i < count && isSub(i)

  /**
    * True when the i-th parameter was given explicitly
    */
  def isSet(i: Int): Boolean = i < count && values(i) >= 0

  override def toString: String = (0 until count)
    .map(i =>
      s"${
          if isSub(i) then
            ":"
          else
            ""
        }${apply(i, -1)}"
    )
    .mkString("CsiParams(", ",", ")")

end CsiParams

/**
  * Receives the actions [[EscapeParser]] decodes from terminal output
  */
trait EscapeHandler:
  /**
    * A printable character
    */
  def print(codePoint: Int): Unit

  /**
    * A C0 control character, such as LF (0x0a) or BEL (0x07)
    */
  def execute(code: Int): Unit

  /**
    * A control sequence: `ESC [ <prefix> <params> <intermediates> <final>`. The prefix is a private
    * marker (`?`, `>`, `=`, or `<`), or 0 when there is none.
    */
  def csi(params: CsiParams, prefix: Char, intermediates: String, finalChar: Char): Unit

  /**
    * An escape sequence: `ESC <intermediates> <final>`, such as `ESC 7` or `ESC ( 0`
    */
  def esc(intermediates: String, finalChar: Char): Unit

  /**
    * An operating system command: `ESC ] <data> BEL` or `ESC ] <data> ESC \`
    */
  def osc(data: String): Unit

end EscapeHandler

/**
  * Decodes the byte stream a program writes to a terminal into printable characters and control
  * actions.
  *
  * This is the DEC ANSI parser state machine described by Paul Williams
  * (vt100.net/emu/dec_ansi_parser), which xterm, VTE, and Alacritty also follow. Input is UTF-8; a
  * code point split across two `feed` calls is decoded once the rest arrives. DCS, SOS, PM, and APC
  * strings are consumed and ignored.
  */
class EscapeParser(handler: EscapeHandler):
  import EscapeParser.*

  private var state: Int    = Ground
  private val params        = CsiParams()
  private var paramStarted  = false
  private var prefix: Char  = 0
  private val intermediates = StringBuilder()
  private val oscData       = StringBuilder()
  private var stringEscape  = false
  // Pending UTF-8 sequence
  private var utf8CodePoint = 0
  private var utf8Remaining = 0
  private var utf8Min       = 0

  /**
    * Feed UTF-8 bytes
    */
  def feed(bytes: Array[Byte]): Unit = feed(bytes, 0, bytes.length)

  /**
    * Feed `length` UTF-8 bytes of `bytes`, starting at `offset`
    */
  def feed(bytes: Array[Byte], offset: Int, length: Int): Unit =
    var i   = offset
    val end = offset + length
    while i < end do
      decodeByte(bytes(i) & 0xff)
      i += 1

  /**
    * Feed text that is already decoded
    */
  def feed(text: String): Unit =
    var i = 0
    while i < text.length do
      val cp = text.codePointAt(i)
      advance(cp)
      i += Character.charCount(cp)

  private def decodeByte(b: Int): Unit =
    if utf8Remaining > 0 then
      if (b & 0xc0) == 0x80 then
        utf8CodePoint = (utf8CodePoint << 6) | (b & 0x3f)
        utf8Remaining -= 1
        if utf8Remaining == 0 then
          val cp = utf8CodePoint
          if cp < utf8Min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) then
            advance(ReplacementChar)
          else
            advance(cp)
      else
        // A truncated sequence: emit a replacement and reprocess this byte
        utf8Remaining = 0
        advance(ReplacementChar)
        decodeByte(b)
    else if b < 0x80 then
      advance(b)
    else if (b & 0xe0) == 0xc0 then
      startUtf8(b & 0x1f, 1, 0x80)
    else if (b & 0xf0) == 0xe0 then
      startUtf8(b & 0x0f, 2, 0x800)
    else if (b & 0xf8) == 0xf0 then
      startUtf8(b & 0x07, 3, 0x10000)
    else
      advance(ReplacementChar)

  private def startUtf8(bits: Int, remaining: Int, min: Int): Unit =
    utf8CodePoint = bits
    utf8Remaining = remaining
    utf8Min = min

  private def clearSequence(): Unit =
    params.clear()
    paramStarted = false
    prefix = 0
    intermediates.clear()

  private def toGround(): Unit = state = Ground

  private def isC0Execute(c: Int): Boolean = c < 0x20 && c != 0x1b && c != 0x18 && c != 0x1a

  /**
    * Process one code point
    */
  private def advance(c: Int): Unit =
    // Transitions that apply in every state
    if c == 0x18 || c == 0x1a then
      // CAN and SUB abort the current sequence
      toGround()
    else if c == 0x1b then
      if state == OscString || state == IgnoredString then
        stringEscape = true
      else
        clearSequence()
        state = Escape
    else if stringEscape then
      stringEscape = false
      if state == OscString then
        dispatchOsc()
      toGround()
      if c != '\\' then
        // ESC ended the string and starts a new sequence
        clearSequence()
        state = Escape
        advance(c)
    else
      state match
        case Ground =>
          if c < 0x20 then
            handler.execute(c)
          else if c == 0x7f || (c >= 0x80 && c < 0xa0) then
            () // DEL and C1 controls are ignored in UTF-8 mode
          else
            handler.print(c)
        case Escape =>
          if isC0Execute(c) then
            handler.execute(c)
          else if c >= 0x20 && c <= 0x2f then
            intermediates.append(c.toChar)
            state = EscapeIntermediate
          else if c == '[' then
            state = CsiEntry
          else if c == ']' then
            oscData.clear()
            state = OscString
          else if c == 'P' || c == 'X' || c == '^' || c == '_' then
            state = IgnoredString
          else if c >= 0x30 && c <= 0x7e then
            toGround()
            handler.esc("", c.toChar)
          else
            toGround()
        case EscapeIntermediate =>
          if isC0Execute(c) then
            handler.execute(c)
          else if c >= 0x20 && c <= 0x2f then
            intermediates.append(c.toChar)
          else if c >= 0x30 && c <= 0x7e then
            toGround()
            handler.esc(intermediates.toString, c.toChar)
          else
            toGround()
        case CsiEntry | CsiParam =>
          if isC0Execute(c) then
            handler.execute(c)
          else if c >= '0' && c <= '9' then
            digit(c - '0')
            state = CsiParam
          else if c == ';' || c == ':' then
            separator(isSub = c == ':')
            state = CsiParam
          else if c >= 0x3c && c <= 0x3f then
            if state == CsiEntry then
              prefix = c.toChar
              state = CsiParam
            else
              state = CsiIgnore
          else if c >= 0x20 && c <= 0x2f then
            intermediates.append(c.toChar)
            state = CsiIntermediate
          else if c >= 0x40 && c <= 0x7e then
            dispatchCsi(c.toChar)
          else
            state = CsiIgnore
        case CsiIntermediate =>
          if isC0Execute(c) then
            handler.execute(c)
          else if c >= 0x20 && c <= 0x2f then
            intermediates.append(c.toChar)
          else if c >= 0x40 && c <= 0x7e then
            dispatchCsi(c.toChar)
          else
            state = CsiIgnore
        case CsiIgnore =>
          if isC0Execute(c) then
            handler.execute(c)
          else if c >= 0x40 && c <= 0x7e then
            toGround()
        case OscString =>
          if c == 0x07 then
            dispatchOsc()
            toGround()
          else if c >= 0x20 && oscData.length < MaxOscLength then
            oscData.appendAll(Character.toChars(c))
        case IgnoredString =>
          () // Consumed until ST
        case _ =>
          toGround()

  private def digit(d: Int): Unit =
    if !paramStarted then
      if params.count < MaxParams then
        params.count += 1
      paramStarted = true
    val i = params.count - 1
    val v = params.values(i)
    params.values(i) = math.min(
      if v < 0 then
        d
      else
        v * 10 + d
      ,
      MaxParamValue
    )

  private def separator(isSub: Boolean): Unit =
    if !paramStarted then
      // An empty parameter, as in `CSI ;5H`
      if params.count < MaxParams then
        params.count += 1
    if params.count < MaxParams then
      params.count += 1
      params.isSub(params.count - 1) = isSub
    paramStarted = true

  private def dispatchCsi(finalChar: Char): Unit =
    toGround()
    handler.csi(params, prefix, intermediates.toString, finalChar)
    clearSequence()

  private def dispatchOsc(): Unit = handler.osc(oscData.toString)

end EscapeParser

object EscapeParser:
  private[term] val MaxParams       = 32
  private[term] val MaxParamValue   = 65535
  private[term] val MaxOscLength    = 65536
  private[term] val ReplacementChar = 0xfffd

  private val Ground             = 0
  private val Escape             = 1
  private val EscapeIntermediate = 2
  private val CsiEntry           = 3
  private val CsiParam           = 4
  private val CsiIntermediate    = 5
  private val CsiIgnore          = 6
  private val OscString          = 7
  private val IgnoredString      = 8
