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
package wvlet.uni.text.parser

import scala.annotation.switch

/**
  * Character constants and helpers used by [[ScannerBase]] and by concrete scanners built on top of
  * it.
  */
object Tokens:
  // Line Feed '\n'
  inline val LF = '\u000A'
  // Form Feed '\f'
  inline val FF = '\u000C'
  // Carriage Return '\r'
  inline val CR = '\u000D'
  // Substitute (SUB), used as the EOF marker
  inline val SU = '\u001A'

  def isLineBreakChar(c: Char): Boolean =
    (c: @switch) match
      case LF | FF | CR | SU =>
        true
      case _ =>
        false

  /**
    * White space character but not a new line (\n).
    */
  def isWhiteSpaceChar(c: Char): Boolean =
    (c: @switch) match
      case ' ' | '\t' | CR =>
        true
      case _ =>
        false

  def isNumberSeparator(ch: Char): Boolean = ch == '_'

  /**
    * Convert a character to an integer value using the given base. Returns -1 on failure.
    */
  def digit2int(ch: Char, base: Int): Int =
    val num =
      if ch <= '9' then
        ch - '0'
      else if 'a' <= ch && ch <= 'z' then
        ch - 'a' + 10
      else if 'A' <= ch && ch <= 'Z' then
        ch - 'A' + 10
      else
        -1
    if 0 <= num && num < base then
      num
    else
      -1

end Tokens
