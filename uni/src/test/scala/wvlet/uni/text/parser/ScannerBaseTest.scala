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

import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.Tokens.*

/**
  * Tiny concrete scanner used to exercise [[ScannerBase]] end-to-end.
  */
object ToyScanner:

  enum ToyToken:
    case EMPTY,
      ERROR,
      EOF,
      IDENT,
      INT_LIT,
      LONG_LIT,
      DECIMAL_LIT,
      EXP_LIT,
      DOUBLE_LIT,
      FLOAT_LIT,
      COMMENT,
      DOC_COMMENT,
      SQ_STRING,
      DQ_STRING,
      TQ_STRING,
      WS,
      BQ_IDENT,
      LET,
      LPAREN,
      RPAREN,
      PLUS,
      EQ

  import ToyToken.*

  given tokenTypeInfo: TokenTypeInfo[ToyToken] with
    def empty: ToyToken                = EMPTY
    def errorToken: ToyToken           = ERROR
    def eofToken: ToyToken             = EOF
    def identifier: ToyToken           = IDENT
    def integerLiteral: ToyToken       = INT_LIT
    def longLiteral: ToyToken          = LONG_LIT
    def decimalLiteral: ToyToken       = DECIMAL_LIT
    def expLiteral: ToyToken           = EXP_LIT
    def doubleLiteral: ToyToken        = DOUBLE_LIT
    def floatLiteral: ToyToken         = FLOAT_LIT
    def commentToken: ToyToken         = COMMENT
    def docCommentToken: ToyToken      = DOC_COMMENT
    def singleQuoteString: ToyToken    = SQ_STRING
    def doubleQuoteString: ToyToken    = DQ_STRING
    def tripleQuoteString: ToyToken    = TQ_STRING
    def whiteSpace: ToyToken           = WS
    def backQuotedIdentifier: ToyToken = BQ_IDENT

    def findToken(s: String): Option[ToyToken] =
      s match
        case "let" =>
          Some(LET)
        case "(" =>
          Some(LPAREN)
        case ")" =>
          Some(RPAREN)
        case "+" =>
          Some(PLUS)
        case "=" =>
          Some(EQ)
        case _ =>
          None

  end tokenTypeInfo

end ToyScanner

import wvlet.uni.text.parser.ToyScanner.ToyToken

class ToyScanner(input: String, config: ScannerConfig = ScannerConfig())
    extends ScannerBase[ToyToken](IArray.from(input.toCharArray), config):

  import ToyScanner.ToyToken.*

  override protected def getNextToken(lastToken: ToyToken): Unit =
    if next.token == EMPTY then
      current.lastOffset = lastCharOffset
      fetchToken()
    else
      current.copyFrom(next)
      resetNextToken()

  override protected def fetchToken(): Unit =
    initOffset()
    (ch: @annotation.switch) match
      case ' ' | '\t' | '\r' | '\n' =>
        getWhiteSpaces()
      case 'a' | 'b' | 'c' | 'd' | 'e' | 'f' | 'g' | 'h' | 'i' | 'j' | 'k' | 'l' | 'm' | 'n' | 'o' |
          'p' | 'q' | 'r' | 's' | 't' | 'u' | 'v' | 'w' | 'x' | 'y' | 'z' | 'A' | 'B' | 'C' | 'D' |
          'E' | 'F' | 'G' | 'H' | 'I' | 'J' | 'K' | 'L' | 'M' | 'N' | 'O' | 'P' | 'Q' | 'R' | 'S' |
          'T' | 'U' | 'V' | 'W' | 'X' | 'Y' | 'Z' | '_' | '$' =>
        getIdentifier()
      case '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
        if ch == '0' then
          scanZero()
        else
          getNumber(10)
      case '+' | '=' | '-' =>
        getOperator()
      case '(' =>
        putChar(ch);
        nextChar();
        finishNamedToken()
      case ')' =>
        putChar(ch);
        nextChar();
        finishNamedToken()
      case '/' =>
        scanSlash()
      case '"' =>
        getDoubleQuoteString(DQ_STRING)
      case '\'' =>
        getSingleQuoteString()
      case Tokens.SU =>
        current.token = EOF
    end match

  end fetchToken

end ToyScanner

class ScannerBaseTest extends UniTest:

  test("tokenize identifiers, keywords, operators") {
    val s      = ToyScanner("let x = 42")
    val tokens =
      List.unfold(()) { _ =>
        val t = s.nextToken()
        if t.token == ToyToken.EOF then
          None
        else
          Some((t.token, t.str), ())
      }
    tokens shouldBe
      List(
        (ToyToken.LET, "let"),
        (ToyToken.IDENT, "x"),
        (ToyToken.EQ, "="),
        (ToyToken.INT_LIT, "42")
      )
  }

  test("tokenize decimal and double literals") {
    val s      = ToyScanner("3.14 2.0d 1.5f 100L")
    val tokens =
      Iterator
        .continually(s.nextToken())
        .takeWhile(_.token != ToyToken.EOF)
        .map(t => (t.token, t.str))
        .toList
    tokens shouldBe
      List(
        (ToyToken.DECIMAL_LIT, "3.14"),
        (ToyToken.DOUBLE_LIT, "2.0d"),
        (ToyToken.FLOAT_LIT, "1.5f"),
        (ToyToken.LONG_LIT, "100")
      )
  }

  test("emit whitespace tokens when configured to do so") {
    val s   = ToyScanner("a b", ScannerConfig(skipWhiteSpace = false))
    val out =
      Iterator.continually(s.nextToken()).takeWhile(_.token != ToyToken.EOF).map(_.token).toList
    out shouldBe List(ToyToken.IDENT, ToyToken.WS, ToyToken.IDENT)
  }

  test("look ahead does not consume") {
    val s   = ToyScanner("a b")
    val cur = s.nextToken()
    cur.str shouldBe "a"
    val peek = s.lookAhead()
    peek.str shouldBe "b"
    // After lookAhead, nextToken should still return the same next token.
    val nxt = s.nextToken()
    nxt.str shouldBe "b"
  }

  test("tokens carry a Span matching their offset") {
    val s  = ToyScanner("let foo")
    val t1 = s.nextToken()
    t1.str shouldBe "let"
    t1.span.start shouldBe 0
    t1.span.end shouldBe 3
    val t2 = s.nextToken()
    t2.str shouldBe "foo"
    t2.span.start shouldBe 4
    t2.span.end shouldBe 7
  }

  test("exponent literal does not absorb a following additive operator") {
    // Regression: a sign inside the exponent is only allowed immediately after
    // `e`/`E`, never after the first exponent digit. `1e1+2` must tokenize as
    // three tokens, not as a single exponent literal.
    val s      = ToyScanner("1e1+2")
    val tokens =
      Iterator
        .continually(s.nextToken())
        .takeWhile(_.token != ToyToken.EOF)
        .map(t => (t.token, t.str))
        .toList
    tokens shouldBe List((ToyToken.EXP_LIT, "1e1"), (ToyToken.PLUS, "+"), (ToyToken.INT_LIT, "2"))
  }

  test("unclosed block comment reports an error instead of hanging") {
    // Regression: without an SU guard in the block-comment loop, scanning
    // `/* unterminated` would loop forever on EOF. `reportErrorToken=true`
    // makes `nextToken()` surface an error token instead of spinning.
    val s = ToyScanner("/* unterminated", ScannerConfig(reportErrorToken = true))
    s.nextToken().token shouldBe ToyToken.ERROR
  }

end ScannerBaseTest
