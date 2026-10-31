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
package wvlet.uni.cli

import scala.collection.mutable.ArrayBuffer

/**
  * Decodes the bytes a terminal sends into [[Key]] events: UTF-8 characters, control keys, and the
  * ANSI/VT100 escape sequences for arrows, Home/End, Delete, and so on, plus bracketed paste.
  *
  * Input can arrive split at any byte, so an incomplete sequence at the end of a chunk is kept
  * until the next [[feed]]. A lone ESC is kept too, because ESC followed by a key is how terminals
  * send Alt + key; call [[flush]] to report it as [[Key.Escape]].
  *
  * @param lineMode
  *   decode piped (non-terminal) input: every byte except `\n` is text, `\n` is Enter, and `\r` is
  *   dropped so that CRLF input reads as one line break
  */
class KeyDecoder(lineMode: Boolean = false):
  private val pending               = ArrayBuffer.empty[Byte]
  private var pasting               = false
  private val pasteBuffer           = ArrayBuffer.empty[Byte]
  private final val ESC             = 0x1b
  private val pasteEnd: Array[Byte] = "\u001b[201~".getBytes("UTF-8")
  private sealed trait Parsed
  private case class Done(key: Option[Key], length: Int) extends Parsed
  private case object Incomplete                         extends Parsed

  /**
    * Decode the next chunk of input and return the keys it completes
    */
  def feed(bytes: Array[Byte]): Seq[Key] =
    pending ++= bytes
    val keys = Seq.newBuilder[Key]
    var i    = 0
    var stop = false
    while !stop && i < pending.length do
      if pasting then
        i = consumePaste(i, keys)
        stop = pasting
      else
        parse(i) match
          case Incomplete =>
            stop = true
          case Done(key, length) =>
            key.foreach(keys += _)
            i += length
    pending.remove(0, i)
    keys.result()

  /**
    * Report input held back as possibly incomplete, such as a lone ESC, as keys
    */
  def flush(): Seq[Key] =
    val keys = Seq.newBuilder[Key]
    if pasting then
      keys += Key.Paste(pasteText(pasteBuffer.toArray ++ pending))
      pasteBuffer.clear()
      pasting = false
    else if pending.nonEmpty then
      if pending(0) == ESC then
        keys += Key.Escape
        pending.remove(0)
      keys ++= feed(Array.emptyByteArray)
      // Anything still incomplete is a truncated UTF-8 character or escape sequence
      if pending.nonEmpty then
        keys += Key.Char(0xfffd)
    pending.clear()
    keys.result()

  private def consumePaste(start: Int, keys: collection.mutable.Builder[Key, Seq[Key]]): Int =
    var i = start
    while i < pending.length do
      if pending(i) == ESC && pending.length - i < pasteEnd.length &&
        pasteEnd.startsWith(pending.slice(i, pending.length))
      then
        // Possibly the start of the end marker; wait for more bytes
        pasteBuffer ++= pending.slice(start, i)
        return i
      else if pending(i) == ESC && pending.slice(i, i + pasteEnd.length).sameElements(pasteEnd) then
        pasteBuffer ++= pending.slice(start, i)
        keys += Key.Paste(pasteText(pasteBuffer.toArray))
        pasteBuffer.clear()
        pasting = false
        return i + pasteEnd.length
      i += 1
    pasteBuffer ++= pending.slice(start, i)
    i

  private def pasteText(bytes: Array[Byte]): String = String(bytes, "UTF-8")
    .replace("\r\n", "\n")
    .replace('\r', '\n')

  private def byteAt(i: Int): Int = pending(i) & 0xff

  private def parse(i: Int): Parsed =
    val b = byteAt(i)
    if lineMode then
      b.toChar match
        case '\n' =>
          Done(Some(Key.Enter), 1)
        case '\r' =>
          Done(None, 1)
        case _ =>
          parseChar(i)
    else if b == ESC then
      parseEscape(i)
    else
      parseSingle(i)

  /**
    * A key that is not an escape sequence: a control byte or a UTF-8 character
    */
  private def parseSingle(i: Int): Parsed =
    val b = byteAt(i)
    if b == 0x7f then
      Done(Some(Key.Backspace), 1)
    else if b == '\r' then
      Done(Some(Key.Enter), 1)
    else if b == 0 || (b >= 0x1c && b < 0x20) then
      // Ctrl-@, Ctrl-\, Ctrl-], Ctrl-^, Ctrl-_
      Done(Some(Key.Ctrl((b + 0x40).toChar)), 1)
    else if b < 0x20 then
      Done(Some(Key.controlKey((b + 0x60).toChar)), 1)
    else
      parseChar(i)

  private def parseChar(i: Int): Parsed =
    val b      = byteAt(i)
    val length =
      if b < 0x80 then
        1
      else if (b & 0xe0) == 0xc0 then
        2
      else if (b & 0xf0) == 0xe0 then
        3
      else if (b & 0xf8) == 0xf0 then
        4
      else
        0
    if length == 0 then
      // A stray continuation byte or an invalid lead byte
      Done(Some(Key.Char(0xfffd)), 1)
    else if i + length > pending.length then
      Incomplete
    else
      val s = String(pending.slice(i, i + length).toArray, "UTF-8")
      Done(Some(Key.Char(s.codePointAt(0))), length)

  private def parseEscape(i: Int): Parsed =
    if i + 1 >= pending.length then
      Incomplete
    else
      byteAt(i + 1).toChar match
        case '[' =>
          parseCSI(i)
        case 'O' =>
          if i + 2 >= pending.length then
            Incomplete
          else
            val key = ss3Key(byteAt(i + 2).toChar).getOrElse(
              Key.Unknown(String(pending.slice(i, i + 3).toArray, "UTF-8"))
            )
            Done(Some(key), 3)
        case '\u001b' =>
          Done(Some(Key.Alt(Key.Escape)), 2)
        case _ =>
          parseSingle(i + 1) match
            case Incomplete =>
              Incomplete
            case Done(key, length) =>
              Done(key.map(Key.Alt(_)), 1 + length)

  /**
    * CSI sequences: ESC [ (parameter bytes 0x30-0x3f)* (intermediate bytes 0x20-0x2f)* (final byte
    * 0x40-0x7e)
    */
  private def parseCSI(i: Int): Parsed =
    var j = i + 2
    while j < pending.length && byteAt(j) >= 0x20 && byteAt(j) <= 0x3f do
      j += 1
    if j >= pending.length then
      Incomplete
    else
      val finalByte = byteAt(j)
      val params    = String(pending.slice(i + 2, j).toArray, "UTF-8")
      val length    = j - i + 1
      if finalByte < 0x40 || finalByte > 0x7e then
        Done(Some(Key.Unknown(String(pending.slice(i, i + length).toArray, "UTF-8"))), length)
      else if finalByte == '~' && params == "200" then
        pasting = true
        Done(None, length)
      else
        val key = csiKey(finalByte.toChar, params).getOrElse(
          Key.Unknown(String(pending.slice(i, i + length).toArray, "UTF-8"))
        )
        Done(Some(key), length)

  private def ss3Key(c: scala.Char): Option[Key] =
    c match
      case 'A' =>
        Some(Key.Up)
      case 'B' =>
        Some(Key.Down)
      case 'C' =>
        Some(Key.Right)
      case 'D' =>
        Some(Key.Left)
      case 'H' =>
        Some(Key.Home)
      case 'F' =>
        Some(Key.End)
      case _ =>
        None

  private def csiKey(finalChar: scala.Char, params: String): Option[Key] =
    val parts = params.split(";")
    // xterm modifier parameter: 1 + (shift:1 | alt:2 | ctrl:4 | meta:8)
    val modifier = parts.lift(1).flatMap(_.toIntOption).map(_ - 1).getOrElse(0)
    val base     =
      finalChar match
        case '~' =>
          parts.headOption.flatMap(_.toIntOption) match
            case Some(1) | Some(7) =>
              Some(Key.Home)
            case Some(2) =>
              Some(Key.Insert)
            case Some(3) =>
              Some(Key.Delete)
            case Some(4) | Some(8) =>
              Some(Key.End)
            case Some(5) =>
              Some(Key.PageUp)
            case Some(6) =>
              Some(Key.PageDown)
            case _ =>
              None
        case 'Z' =>
          Some(Key.BackTab)
        case c =>
          ss3Key(c)
    if (modifier & (2 | 4 | 8)) != 0 then
      base.map(Key.Alt(_))
    else
      base

  end csiKey

end KeyDecoder
