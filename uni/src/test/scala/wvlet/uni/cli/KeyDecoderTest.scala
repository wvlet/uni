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

import wvlet.uni.test.UniTest

class KeyDecoderTest extends UniTest:
  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")
  private def decode(s: String): Seq[Key]   = KeyDecoder().feed(bytes(s))

  test("decode characters and control keys") {
    decode("ab\r\t\u007f\u0001\n") shouldBe
      Seq(
        Key.char('a'),
        Key.char('b'),
        Key.Enter,
        Key.Tab,
        Key.Backspace,
        Key.Ctrl('a'),
        Key.Ctrl('j')
      )
  }

  test("decode UTF-8 characters split across chunks") {
    val decoder = KeyDecoder()
    val b       = bytes("日本")
    decoder.feed(b.take(2)) shouldBe Seq.empty
    decoder.feed(b.slice(2, 4)) shouldBe Seq(Key.Char('日'.toInt))
    decoder.feed(b.drop(4)) shouldBe Seq(Key.Char('本'.toInt))
    decoder.feed(bytes("😀")) shouldBe Seq(Key.Char(0x1f600))
  }

  test("decode cursor and editing keys") {
    decode("\u001b[A\u001b[B\u001b[C\u001b[D") shouldBe Seq(Key.Up, Key.Down, Key.Right, Key.Left)
    decode("\u001bOH\u001bOF\u001b[H\u001b[F") shouldBe Seq(Key.Home, Key.End, Key.Home, Key.End)
    decode("\u001b[1~\u001b[3~\u001b[4~\u001b[5~\u001b[6~\u001b[2~") shouldBe
      Seq(Key.Home, Key.Delete, Key.End, Key.PageUp, Key.PageDown, Key.Insert)
    decode("\u001b[Z") shouldBe Seq(Key.BackTab)
  }

  test("decode Alt and modified arrows") {
    decode("\u001bb\u001b\u007f\u001b\r") shouldBe
      Seq(Key.Alt(Key.char('b')), Key.Alt(Key.Backspace), Key.Alt(Key.Enter))
    decode("\u001b[1;5D\u001b[1;3C\u001b[1;2A") shouldBe
      Seq(Key.Alt(Key.Left), Key.Alt(Key.Right), Key.Up)
  }

  test("keep an incomplete escape sequence until the next chunk") {
    val decoder = KeyDecoder()
    decoder.feed(bytes("x\u001b[")) shouldBe Seq(Key.char('x'))
    decoder.feed(bytes("A")) shouldBe Seq(Key.Up)
  }

  test("report a lone ESC on flush") {
    val decoder = KeyDecoder()
    decoder.feed(bytes("\u001b")) shouldBe Seq.empty
    decoder.flush() shouldBe Seq(Key.Escape)
  }

  test("report unknown sequences") {
    decode("\u001b[99x") shouldBe Seq(Key.Unknown("\u001b[99x"))
  }

  test("decode bracketed paste, even when split") {
    decode("\u001b[200~line1\r\nline2\u001b[201~z") shouldBe
      Seq(Key.Paste("line1\nline2"), Key.char('z'))

    val decoder = KeyDecoder()
    decoder.feed(bytes("\u001b[200~ab")) shouldBe Seq.empty
    decoder.feed(bytes("c\u001b[20")) shouldBe Seq.empty
    decoder.feed(bytes("1~")) shouldBe Seq(Key.Paste("abc"))
  }

  test("read piped input as plain lines in line mode") {
    KeyDecoder(lineMode = true).feed(bytes("a\tb\r\n\u001b\n")) shouldBe
      Seq(Key.char('a'), Key.char('\t'), Key.char('b'), Key.Enter, Key.Char(0x1b), Key.Enter)
  }

  test("parse ^X key sequences") {
    Key.parseSequence("^J^R") shouldBe Seq(Key.Ctrl('j'), Key.Ctrl('r'))
    Key.parseSequence("^Mx") shouldBe Seq(Key.Enter, Key.char('x'))
  }

end KeyDecoderTest
