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

/**
  * A key press decoded from terminal input by [[KeyDecoder]].
  */
enum Key:
  /**
    * A printable character, as a Unicode code point
    */
  case Char(codePoint: Int)

  /**
    * Ctrl + a letter (`'a'` to `'z'`), or one of `@ [ \ ] ^ _`. Enter, Tab, Backspace, and Escape
    * are reported as their own keys, not as Ctrl('m'), Ctrl('i'), Ctrl('h'), or Ctrl('[').
    */
  case Ctrl(letter: scala.Char)

  /**
    * Alt (Meta) + a key. Terminals send it as ESC followed by the key. Ctrl + an arrow key is also
    * reported as Alt + the arrow, since both mean word movement in line editors.
    */
  case Alt(key: Key)

  /**
    * Text pasted while bracketed paste mode is on. Newlines in it are kept.
    */
  case Paste(text: String)

  /**
    * An escape sequence that the decoder does not recognize, kept for custom bindings
    */
  case Unknown(sequence: String)

  case Enter,
    Tab,
    BackTab,
    Backspace,
    Delete,
    Insert,
    Escape

  case Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown

end Key

object Key:
  /**
    * The key for a printable character
    */
  def char(c: scala.Char): Key = Key.Char(c.toInt)

  /**
    * Parse a key sequence written in the `^X` notation of readline and JLine (`^J^R` is Ctrl-J
    * followed by Ctrl-R). Other characters stand for themselves.
    */
  def parseSequence(s: String): Seq[Key] =
    val keys = Seq.newBuilder[Key]
    var i    = 0
    while i < s.length do
      if s.charAt(i) == '^' && i + 1 < s.length then
        keys += controlKey(s.charAt(i + 1).toLower)
        i += 2
      else
        val cp = s.codePointAt(i)
        keys += Key.Char(cp)
        i += Character.charCount(cp)
    keys.result()

  /**
    * The key that a control byte (0x00-0x1f) stands for
    */
  private[cli] def controlKey(c: scala.Char): Key =
    c match
      case 'm' =>
        Key.Enter
      case 'i' =>
        Key.Tab
      case 'h' =>
        Key.Backspace
      case '[' =>
        Key.Escape
      case other =>
        Key.Ctrl(other)

end Key
