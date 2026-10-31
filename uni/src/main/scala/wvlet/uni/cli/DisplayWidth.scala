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
  * The number of terminal columns text occupies: East Asian wide characters and most emoji take two
  * columns, combining marks and zero-width characters take none, and ANSI escape sequences are not
  * counted.
  */
object DisplayWidth:
  private val ansiPattern =
    "\u001b\\[[0-?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)".r

  /**
    * Remove ANSI escape sequences (CSI and OSC), such as the colors [[Tint]] adds
    */
  def stripAnsi(s: String): String =
    if s.indexOf('\u001b') < 0 then
      s
    else
      ansiPattern.replaceAllIn(s, "")

  /**
    * The display width of a string, ignoring ANSI escape sequences
    */
  def of(s: String): Int =
    val text  = stripAnsi(s)
    var width = 0
    var i     = 0
    while i < text.length do
      val cp = text.codePointAt(i)
      width += ofCodePoint(cp)
      i += Character.charCount(cp)
    width

  /**
    * The display width of a single code point: 0, 1, or 2
    */
  def ofCodePoint(cp: Int): Int =
    if cp == 0 || cp == 0x200b || cp == 0x200c || cp == 0x200d || cp == 0xfeff then
      0
    else if cp < 0x20 || (cp >= 0x7f && cp < 0xa0) then
      0
    else if isCombining(cp) then
      0
    else if isWide(cp) then
      2
    else
      1

  private def isCombining(cp: Int): Boolean =
    (cp >= 0x0300 && cp <= 0x036f) ||
      (cp >= 0x1ab0 && cp <= 0x1aff) ||
      (cp >= 0x1dc0 && cp <= 0x1dff) ||
      (cp >= 0x20d0 && cp <= 0x20ff) ||
      (cp >= 0xfe00 && cp <= 0xfe0f) ||
      (cp >= 0xfe20 && cp <= 0xfe2f) ||
      (cp >= 0xe0100 && cp <= 0xe01ef)

  // East Asian Wide (W) and Fullwidth (F) ranges, plus the emoji blocks terminals render wide
  private def isWide(cp: Int): Boolean =
    (cp >= 0x1100 && cp <= 0x115f) || cp == 0x2329 || cp == 0x232a ||
      (cp >= 0x231a && cp <= 0x231b) ||
      (cp >= 0x23e9 && cp <= 0x23ec) ||
      (cp >= 0x25fd && cp <= 0x25fe) ||
      (cp >= 0x2614 && cp <= 0x2615) ||
      (cp >= 0x2648 && cp <= 0x2653) ||
      (cp >= 0x26aa && cp <= 0x26ab) ||
      (cp >= 0x26bd && cp <= 0x26be) ||
      (cp >= 0x26c4 && cp <= 0x26c5) || cp == 0x26a1 || cp == 0x26ce || cp == 0x26d4 ||
      cp == 0x26ea ||
      (cp >= 0x26f2 && cp <= 0x26f5) || cp == 0x26fa || cp == 0x26fd || cp == 0x2705 ||
      (cp >= 0x270a && cp <= 0x270b) || cp == 0x2728 || cp == 0x274c || cp == 0x274e ||
      (cp >= 0x2753 && cp <= 0x2755) || cp == 0x2757 ||
      (cp >= 0x2795 && cp <= 0x2797) || cp == 0x27b0 || cp == 0x27bf ||
      (cp >= 0x2b1b && cp <= 0x2b1c) || cp == 0x2b50 || cp == 0x2b55 ||
      (cp >= 0x2e80 && cp <= 0x303e) ||
      (cp >= 0x3041 && cp <= 0x33ff) ||
      (cp >= 0x3400 && cp <= 0x4dbf) ||
      (cp >= 0x4e00 && cp <= 0x9fff) ||
      (cp >= 0xa000 && cp <= 0xa4cf) ||
      (cp >= 0xa960 && cp <= 0xa97f) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xfe10 && cp <= 0xfe19) ||
      (cp >= 0xfe30 && cp <= 0xfe6f) ||
      (cp >= 0xff00 && cp <= 0xff60) ||
      (cp >= 0xffe0 && cp <= 0xffe6) ||
      (cp >= 0x16fe0 && cp <= 0x18aff) ||
      (cp >= 0x1b000 && cp <= 0x1b2ff) ||
      (cp >= 0x1f004 && cp <= 0x1f004) || cp == 0x1f0cf || cp == 0x1f18e ||
      (cp >= 0x1f191 && cp <= 0x1f19a) ||
      (cp >= 0x1f200 && cp <= 0x1f251) ||
      (cp >= 0x1f300 && cp <= 0x1f64f) ||
      (cp >= 0x1f680 && cp <= 0x1f6ff) ||
      (cp >= 0x1f7e0 && cp <= 0x1f7eb) ||
      (cp >= 0x1f90c && cp <= 0x1f9ff) ||
      (cp >= 0x1fa70 && cp <= 0x1faff) ||
      (cp >= 0x20000 && cp <= 0x2fffd) ||
      (cp >= 0x30000 && cp <= 0x3fffd)

end DisplayWidth
