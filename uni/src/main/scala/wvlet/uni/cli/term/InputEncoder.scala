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

import wvlet.uni.cli.Key

/**
  * A mouse button
  */
enum MouseButton:
  case Left,
    Middle,
    Right,
    None,
    WheelUp,
    WheelDown

/**
  * What happened to the mouse
  */
enum MouseAction:
  case Press,
    Release,
    Move

/**
  * A mouse event at a cell, with `row` and `col` counted from 0
  */
case class MouseEvent(
    action: MouseAction,
    button: MouseButton,
    row: Int,
    col: Int,
    shift: Boolean = false,
    alt: Boolean = false,
    ctrl: Boolean = false
)

/**
  * Encodes user input as the bytes an xterm sends to the program: keys, pasted text, and mouse
  * events. The encoding depends on modes the program set, such as application cursor keys and
  * bracketed paste.
  */
class InputEncoder(modes: () => InputModes):

  /**
    * Encode a key decoded by [[wvlet.uni.cli.KeyDecoder]] or built with [[Key]]
    */
  def key(k: Key): String =
    k match
      case Key.Char(cp) =>
        String(Character.toChars(cp))
      case Key.Ctrl(c) =>
        ctrlChar(c).getOrElse("")
      case Key.Alt(inner) =>
        "\u001b" + key(inner)
      case Key.Paste(text) =>
        paste(text)
      case Key.Unknown(seq) =>
        seq
      case Key.Enter =>
        "\r"
      case Key.Tab =>
        "\t"
      case Key.BackTab =>
        "\u001b[Z"
      case Key.Backspace =>
        "\u007f"
      case Key.Escape =>
        "\u001b"
      case Key.Delete =>
        "\u001b[3~"
      case Key.Insert =>
        "\u001b[2~"
      case Key.PageUp =>
        "\u001b[5~"
      case Key.PageDown =>
        "\u001b[6~"
      case Key.Up =>
        cursorKey('A', 0)
      case Key.Down =>
        cursorKey('B', 0)
      case Key.Right =>
        cursorKey('C', 0)
      case Key.Left =>
        cursorKey('D', 0)
      case Key.Home =>
        cursorKey('H', 0)
      case Key.End =>
        cursorKey('F', 0)

  /**
    * Encode a key given as a W3C `KeyboardEvent.key` value ("a", "Enter", "ArrowUp", "F5"). Returns
    * None for keys that send nothing, such as a lone "Shift". Meta is treated like Alt.
    */
  def key(
      name: String,
      ctrl: Boolean = false,
      alt: Boolean = false,
      shift: Boolean = false,
      meta: Boolean = false
  ): Option[String] =
    val altLike = alt || meta
    // xterm's modifier parameter: 1 + shift(1) + alt(2) + ctrl(4)
    val mod =
      (
        if shift then
          1
        else
          0
      ) + (
        if altLike then
          2
        else
          0
      ) + (
        if ctrl then
          4
        else
          0
      )
    def withAlt(s: String): String =
      if altLike then
        "\u001b" + s
      else
        s
    name match
      case "Enter" =>
        Some(withAlt("\r"))
      case "Tab" =>
        Some(
          if shift then
            "\u001b[Z"
          else
            withAlt("\t")
        )
      case "Backspace" =>
        Some(
          withAlt(
            if ctrl then
              "\b"
            else
              "\u007f"
          )
        )
      case "Escape" =>
        Some(withAlt("\u001b"))
      case "ArrowUp" =>
        Some(cursorKey('A', mod))
      case "ArrowDown" =>
        Some(cursorKey('B', mod))
      case "ArrowRight" =>
        Some(cursorKey('C', mod))
      case "ArrowLeft" =>
        Some(cursorKey('D', mod))
      case "Home" =>
        Some(cursorKey('H', mod))
      case "End" =>
        Some(cursorKey('F', mod))
      case "Insert" =>
        Some(tildeKey(2, mod))
      case "Delete" =>
        Some(tildeKey(3, mod))
      case "PageUp" =>
        Some(tildeKey(5, mod))
      case "PageDown" =>
        Some(tildeKey(6, mod))
      case f if f.length >= 2 && f.charAt(0) == 'F' && f.substring(1).forall(_.isDigit) =>
        functionKey(f.substring(1).toInt, mod)
      case s if s.codePointCount(0, s.length) == 1 =>
        if ctrl then
          ctrlChar(s.charAt(0).toLower).map(withAlt)
        else
          Some(withAlt(s))
      case _ =>
        None

    end match

  end key

  /**
    * Encode pasted text. Newlines become carriage returns, as when typed, and the text is wrapped
    * in `ESC [200~` and `ESC [201~` when the program enabled bracketed paste.
    */
  def paste(text: String): String =
    val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
    if modes().bracketedPaste then
      // Drop an embedded end marker so pasted text cannot end the paste early
      s"\u001b[200~${normalized.replace("\u001b[201~", "")}\u001b[201~"
    else
      normalized

  /**
    * Encode a mouse event, or None when the program does not track this kind of event
    */
  def mouse(e: MouseEvent): Option[String] =
    val m       = modes()
    val tracked =
      m.mouseTracking match
        case MouseTracking.Off =>
          false
        case MouseTracking.Click =>
          e.action != MouseAction.Move
        case MouseTracking.Drag =>
          e.action != MouseAction.Move || e.button != MouseButton.None
        case MouseTracking.Any =>
          true
    if !tracked then
      None
    else
      var code =
        e.button match
          case MouseButton.Left =>
            0
          case MouseButton.Middle =>
            1
          case MouseButton.Right =>
            2
          case MouseButton.None =>
            3
          case MouseButton.WheelUp =>
            64
          case MouseButton.WheelDown =>
            65
      if e.action == MouseAction.Move then
        code += 32
      if e.shift then
        code += 4
      if e.alt then
        code += 8
      if e.ctrl then
        code += 16
      if m.sgrMouse then
        val suffix =
          if e.action == MouseAction.Release then
            'm'
          else
            'M'
        Some(s"\u001b[<${code};${e.col + 1};${e.row + 1}${suffix}")
      else
        // X10 encoding: releases report button 3, and coordinates above 222 cannot be sent
        if e.action == MouseAction.Release then
          code = (code & ~3) | 3
        val x = e.col + 1 + 32
        val y = e.row + 1 + 32
        if x > 255 || y > 255 then
          None
        else
          Some(s"\u001b[M${(code + 32).toChar}${x.toChar}${y.toChar}")

    end if

  end mouse

  private def ctrlChar(c: Char): Option[String] =
    if c >= 'a' && c <= 'z' then
      Some((c - 'a' + 1).toChar.toString)
    else
      c match
        case '@' | ' ' | '2' =>
          Some("\u0000")
        case '[' | '3' =>
          Some("\u001b")
        case '\\' | '4' =>
          Some("\u001c")
        case ']' | '5' =>
          Some("\u001d")
        case '^' | '6' =>
          Some("\u001e")
        case '_' | '-' | '7' | '/' =>
          Some("\u001f")
        case '8' | '?' =>
          Some("\u007f")
        case _ =>
          None

  // Arrow keys, Home, and End: CSI or SS3 form, or `CSI 1;<mod><final>` with modifiers
  private def cursorKey(finalChar: Char, mod: Int): String =
    if mod > 0 then
      s"\u001b[1;${mod + 1}${finalChar}"
    else if modes().applicationCursorKeys then
      s"\u001bO${finalChar}"
    else
      s"\u001b[${finalChar}"

  private def tildeKey(code: Int, mod: Int): String =
    if mod > 0 then
      s"\u001b[${code};${mod + 1}~"
    else
      s"\u001b[${code}~"

  private def functionKey(n: Int, mod: Int): Option[String] =
    if n >= 1 && n <= 4 then
      val c = "PQRS".charAt(n - 1)
      Some(
        if mod > 0 then
          s"\u001b[1;${mod + 1}${c}"
        else
          s"\u001bO${c}"
      )
    else
      val codes = Map(5 -> 15, 6 -> 17, 7 -> 18, 8 -> 19, 9 -> 20, 10 -> 21, 11 -> 23, 12 -> 24)
      codes.get(n).map(tildeKey(_, mod))

end InputEncoder
