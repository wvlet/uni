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
package wvlet.uni.text.cron

import wvlet.uni.text.Span
import wvlet.uni.text.parser.LineReader
import wvlet.uni.text.parser.ScannerBase
import wvlet.uni.text.parser.ScannerConfig
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.text.parser.TokenTypeInfo
import wvlet.uni.text.parser.Tokens

/**
  * Tokens of a cron expression. Whitespace is a token because it separates the fields.
  */
enum CronToken:
  case EMPTY,
    ERROR,
    EOF,
    WS,
    // A decimal number
    NUMBER,
    // A month or day-of-week name (JAN, MON)
    NAME,
    // A macro such as @daily
    MACRO,
    STAR,
    QUESTION,
    COMMA,
    HYPHEN,
    SLASH

object CronToken:
  given TokenTypeInfo[CronToken] with
    def empty: CronToken                = EMPTY
    def errorToken: CronToken           = ERROR
    def eofToken: CronToken             = EOF
    def identifier: CronToken           = NAME
    def integerLiteral: CronToken       = NUMBER
    def longLiteral: CronToken          = NUMBER
    def decimalLiteral: CronToken       = ERROR
    def expLiteral: CronToken           = ERROR
    def doubleLiteral: CronToken        = ERROR
    def floatLiteral: CronToken         = ERROR
    def commentToken: CronToken         = ERROR
    def docCommentToken: CronToken      = ERROR
    def singleQuoteString: CronToken    = ERROR
    def doubleQuoteString: CronToken    = ERROR
    def tripleQuoteString: CronToken    = ERROR
    def whiteSpace: CronToken           = WS
    def backQuotedIdentifier: CronToken = ERROR

    def findToken(s: String): Option[CronToken] =
      s match
        case "*" =>
          Some(STAR)
        case "?" =>
          Some(QUESTION)
        case "," =>
          Some(COMMA)
        case "-" =>
          Some(HYPHEN)
        case "/" =>
          Some(SLASH)
        case s if s.startsWith("@") =>
          Some(MACRO)
        case _ =>
          None

  end given

end CronToken

/**
  * Tokenizes a cron expression. Unexpected characters throw [[TextParseException]] with the line
  * and column.
  */
class CronScanner(expr: String)
    extends ScannerBase[CronToken](
      IArray.from(expr.toCharArray),
      ScannerConfig(skipWhiteSpace = false)
    ):
  import CronToken.*

  override protected def getNextToken(lastToken: CronToken): Unit =
    if next.token == EMPTY then
      current.lastOffset = lastCharOffset
      fetchToken()
    else
      current.copyFrom(next)
      resetNextToken()

  override protected def fetchToken(): Unit =
    initOffset()
    ch match
      case ' ' | '\t' =>
        getWhiteSpaces()
      case c if '0' <= c && c <= '9' =>
        while '0' <= ch && ch <= '9' do
          putChar(ch)
          nextChar()
        flushTokenString()
        current.token = NUMBER
      case c if ('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z') =>
        getIdentifier()
      case '@' =>
        getIdentifier()
      case '*' | '?' | ',' | '-' | '/' =>
        putChar(ch)
        nextChar()
        finishNamedToken()
      case Tokens.SU =>
        current.token = EOF
        current.str = ""
      case c =>
        val shown =
          if c < ' ' then
            f"\\u${c.toInt}%04x"
          else
            c.toString
        throw CronScanner.error(expr, s"unexpected character '${shown}'", offset)
    end match

  end fetchToken

end CronScanner

object CronScanner:
  def error(expr: String, message: String, offset: Int): TextParseException = TextParseException(
    LineReader.formatError(expr, message, offset),
    offset,
    Span.at(offset)
  )
