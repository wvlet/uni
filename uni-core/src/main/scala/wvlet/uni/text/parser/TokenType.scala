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

/**
  * Typeclass describing the special token values a [[ScannerBase]] implementation needs to know
  * about. A concrete scanner provides a `given` instance for its own `Token` enum so that
  * [[ScannerBase]] can be Token-generic.
  */
trait TokenTypeInfo[Token]:
  def empty: Token
  def errorToken: Token
  def eofToken: Token
  def identifier: Token
  def findToken(s: String): Option[Token]
  def integerLiteral: Token
  def longLiteral: Token
  def decimalLiteral: Token
  def expLiteral: Token
  def doubleLiteral: Token
  def floatLiteral: Token
  def commentToken: Token
  def docCommentToken: Token
  def singleQuoteString: Token
  def doubleQuoteString: Token
  def tripleQuoteString: Token
  def whiteSpace: Token
  def backQuotedIdentifier: Token
