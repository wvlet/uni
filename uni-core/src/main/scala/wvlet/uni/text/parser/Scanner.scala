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

import wvlet.uni.log.LogSupport
import wvlet.uni.text.Span
import wvlet.uni.text.parser.Scanner.Indented
import wvlet.uni.text.parser.Scanner.Region
import wvlet.uni.text.parser.Tokens.*

import scala.annotation.switch
import scala.annotation.tailrec

/**
  * A read-only token emitted by [[ScannerBase]].
  */
case class TokenData[Token](token: Token, str: String, offset: Int, length: Int):
  override def toString: String = f"[${offset}%3d:${length}%2d] ${token}%10s: ${str}"

  def span: Span = Span(offset, offset + length, 0)

/**
  * Mutable state used internally by [[ScannerBase]] to track the token currently being read and the
  * previous/next lookahead tokens.
  */
class ScanState[Token](var token: Token, startFrom: Int = 0):
  override def toString: String = s"'${str}' <${token}> (${lastOffset}-${offset})"

  // The string value of the token
  var str: String = ""

  // The 1-character ahead offset of the last read character
  var offset: Int = startFrom
  // The offset of the character immediately before the current token
  var lastOffset: Int = startFrom
  // The offset of the newline immediately before the current token, or -1 if
  // the current token is not the first one after a newline
  var lineOffset: Int = -1

  def copyFrom(s: ScanState[Token]): Unit =
    token = s.token
    str = s.str
    offset = s.offset
    lastOffset = s.lastOffset
    lineOffset = s.lineOffset

  def toTokenData(lastCharOffset: Int): TokenData[Token] = TokenData(
    token,
    str,
    offset,
    lastCharOffset - offset
  )

  def isAfterLineEnd: Boolean = lineOffset >= 0
end ScanState

/**
  * Configuration for [[ScannerBase]].
  *
  * @param startFrom
  *   initial character offset to begin scanning from
  * @param skipComments
  *   if true, comment tokens are consumed silently and exposed only via
  *   [[ScannerBase.getCommentTokens]]
  * @param skipWhiteSpace
  *   if true, whitespace is skipped instead of emitted as a token
  * @param reportErrorToken
  *   if true, tokenization errors are reported as errorToken values (and raised via
  *   [[TextParseException]] internally) instead of only logged
  * @param debugScanner
  *   enable verbose scanner debug logging
  */
case class ScannerConfig(
    startFrom: Int = 0,
    skipComments: Boolean = false,
    skipWhiteSpace: Boolean = true,
    reportErrorToken: Boolean = false,
    debugScanner: Boolean = false
)

/**
  * A reusable scanner implementation, generic over the token type. Concrete scanners extend this
  * class, provide a `given TokenTypeInfo[Token]` instance, and implement [[fetchToken]] and
  * [[getNextToken]] for language-specific tokens (keywords, operators).
  *
  * The scanner operates on an in-memory character buffer. To read from a string, convert it with
  * `IArray.from(s.toCharArray)` before constructing the scanner.
  */
abstract class ScannerBase[Token](protected val buf: IArray[Char], config: ScannerConfig)(using
    tokenTypeInfo: TokenTypeInfo[Token]
) extends LogSupport:

  protected var current: ScanState[Token] = ScanState(tokenTypeInfo.empty, config.startFrom)

  // Preserve token history
  protected var prev: ScanState[Token] = ScanState(
    tokenTypeInfo.empty,
    startFrom = config.startFrom
  )

  protected var next: ScanState[Token] = ScanState(
    tokenTypeInfo.empty,
    startFrom = config.startFrom
  )

  protected val tokenBuffer                           = TokenBuffer()
  protected var currentRegion: Region                 = Indented(0, null)
  protected var commentBuffer: List[TokenData[Token]] = Nil

  // The last read character
  protected var ch: Char = scala.compiletime.uninitialized
  // The offset +1 of the last read character
  protected var charOffset: Int = config.startFrom
  // The offset before the last read character
  protected var lastCharOffset: Int = config.startFrom
  // The start offset of the current line
  protected var lineStartOffset: Int = config.startFrom

  inline protected def offset: Int = current.offset
  inline private def length: Int   = buf.length

  // Initialization: populate the first character
  nextChar()

  /**
    * Push the current token into the comment list.
    */
  private def pushComment(): Unit =
    val commentToken = current.toTokenData(lastCharOffset)
    commentBuffer = commentToken :: commentBuffer
    if config.skipComments then
      if config.debugScanner then
        debug(s"skipped comment: ${commentToken}")
      fetchToken()

  /**
    * The list of comment tokens seen so far. This list is accumulated across every call to
    * [[nextToken]]; call [[clearCommentTokens]] to drop already-consumed entries.
    */
  def getCommentTokens(): List[TokenData[Token]] = commentBuffer

  /**
    * Drop the accumulated list of comment tokens. Call this periodically when scanning large or
    * long-running inputs to keep memory bounded.
    */
  def clearCommentTokens(): Unit = commentBuffer = Nil

  def resetNextToken(): Unit = next.token = tokenTypeInfo.empty

  /**
    * Consume and return the next token.
    */
  def nextToken(): TokenData[Token] =
    val lastToken = current.token
    try
      getNextToken(lastToken)

      val t = current.toTokenData(lastCharOffset)
      if config.debugScanner then
        debug(s"${currentRegion} $t")
      t
    catch
      case e: TextParseException if config.reportErrorToken =>
        current.token = tokenTypeInfo.errorToken
        currentRegion = Indented(0, null)
        val t = current.toTokenData(lastCharOffset)
        nextChar()
        t

  /**
    * The current token.
    */
  def currentToken: TokenData[Token] = current.toTokenData(lastCharOffset)

  /**
    * Peek the next token without consuming it.
    */
  def lookAhead(): TokenData[Token] =
    if next.token == tokenTypeInfo.empty then
      // prev <- current, current <- newToken
      peekAhead()
      // current, next
      shiftTokenHistory()
    next.toTokenData(lastCharOffset)

  protected def nextChar(): Unit =
    val index = charOffset
    lastCharOffset = index
    charOffset = index + 1
    if index >= length then
      // SU marks end of file
      ch = SU
    else
      val c = buf(index)
      ch = c
      if c < ' ' then
        fetchLineEnd()

  /**
    * Fetch the next raw character including CR and LF.
    */
  protected def nextRawChar(): Unit =
    val index = charOffset
    lastCharOffset = index
    charOffset = index + 1
    if index >= length then
      ch = SU
    else
      ch = buf(index)

  private def fetchLineEnd(): Unit =
    // Collapse CR LF into a single LF
    if ch == CR then
      if charOffset < length && buf(offset) == LF then
        current.offset += 1
        ch = LF

    // New line: remember the start offset
    if ch == LF || ch == FF then
      lineStartOffset = charOffset

  /**
    * Look ahead at the character at the given offset.
    * @param offset
    *   0 for the next character (default)
    */
  protected def lookAheadChar(offset: Int = 0): Char =
    val index = charOffset + offset
    if index >= length then
      SU
    else
      buf(index)

  protected def checkNoTrailingNumberSeparator(): Unit =
    if tokenBuffer.nonEmpty && isNumberSeparator(tokenBuffer.last) then
      reportError("trailing number separator", offset)

  protected def reportError(msg: String, offset: Int): Unit =
    if config.reportErrorToken then
      throw TextParseException(msg, offset)
    else
      error(s"${msg} at offset ${offset}")

  protected def consume(expectedChar: Char): Unit =
    if ch != expectedChar then
      reportError(s"expected '${expectedChar}', but found '${ch}'", offset)
    nextChar()

  protected def shiftTokenHistory(): Unit =
    next.copyFrom(current)
    current.copyFrom(prev)

  protected def putChar(ch: Char): Unit = tokenBuffer.append(ch)

  /**
    * Read the next token and update the current token data. If the next token has already been read
    * via [[lookAhead]], update the current token data with the cached next token.
    */
  protected def getNextToken(lastToken: Token): Unit

  protected def fetchToken(): Unit

  protected def initOffset(): Unit =
    current.offset = charOffset - 1
    current.lineOffset =
      if current.lastOffset < lineStartOffset then
        lineStartOffset
      else
        -1
    trace(
      s"fetchToken[${current}]: '${String.valueOf(
          ch
        )}' charOffset:${charOffset} lastCharOffset:${lastCharOffset}, lineStartOffset:${lineStartOffset}"
    )

  protected def peekAhead(): Unit =
    prev.copyFrom(current)
    getNextToken(current.token)

  /**
    * Set the token string and clear the buffer.
    */
  protected def flushTokenString(): String =
    val str = tokenBuffer.toString
    current.str = str
    tokenBuffer.clear()
    str

  protected def finishNamedToken(target: ScanState[Token] = current): Unit =
    val currentTokenStr = flushTokenString()
    trace(s"finishNamedToken at ${current}: '${currentTokenStr}'")
    tokenTypeInfo.findToken(currentTokenStr) match
      case Some(tokenType) =>
        target.token = tokenType
      case _ =>
        target.token = tokenTypeInfo.identifier

  protected def getWhiteSpaces(): Unit =
    def getWSRest(): Unit =
      if isWhiteSpaceChar(ch) then
        putChar(ch)
        nextChar()
        getWSRest()
      else
        current.token = tokenTypeInfo.whiteSpace
        flushTokenString()

    if config.skipWhiteSpace then
      // Skip whitespace without pushing it into the buffer
      nextChar()
      fetchToken()
    else
      putChar(ch)
      nextChar()
      getWSRest()

  protected def getIdentifier(): Unit =
    putChar(ch)
    nextChar()
    getIdentRest()

  protected def getOperator(): Unit =
    putChar(ch)
    nextChar()
    getOperatorRest()

  protected def scanHyphen(): Unit =
    // first hyphen
    putChar(ch)
    nextChar()
    // second hyphen
    if ch == '-' then
      putChar(ch)
      nextChar()
      // third hyphen
      if ch == '-' then
        putChar(ch)
        nextRawChar()
        // triple hyphen -> Doc comment
        getDocComment()
      else
        getLineComment()
    else
      getOperatorRest()

  protected def scanZero(): Unit =
    var base: Int                = 10
    def fetchLeadingZero(): Unit =
      putChar(ch)
      nextChar()
      ch match
        case 'x' | 'X' =>
          base = 16
          putChar(ch)
          nextChar()
        case _ =>
          base = 10
      if base != 10 && !isNumberSeparator(ch) && digit2int(ch, base) < 0 then
        reportError("invalid literal number", offset)

    fetchLeadingZero()
    getNumber(base)

  protected def scanDot(): Unit =
    val next = lookAheadChar()
    if '0' <= next && next <= '9' then
      // .01, .1, .123, etc.
      getNumber(10)
    else
      nextChar()
      putChar('.')
      getOperatorRest()

  protected def scanSlash(): Unit =
    putChar(ch)
    nextChar()
    ch match
      case '/' =>
        putChar(ch)
        nextChar()
        getOperatorRest()
      case '*' =>
        putChar(ch)
        nextChar()
        getBlockComment()
      case _ =>
        getOperatorRest()

  @tailrec
  final protected def getIdentRest(): Unit =
    trace(s"getIdentRest[${offset}]: ch: '${String.valueOf(ch)}'")
    (ch: @switch) match
      case 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | 'H' | 'I' | 'J' | 'K' | 'L' | 'M' | 'N' | 'O' |
          'P' | 'Q' | 'R' | 'S' | 'T' | 'U' | 'V' | 'W' | 'X' | 'Y' | 'Z' | '$' | 'a' | 'b' | 'c' |
          'd' | 'e' | 'f' | 'g' | 'h' | 'i' | 'j' | 'k' | 'l' | 'm' | 'n' | 'o' | 'p' | 'q' | 'r' |
          's' | 't' | 'u' | 'v' | 'w' | 'x' | 'y' | 'z' | '0' | '1' | '2' | '3' | '4' | '5' | '6' |
          '7' | '8' | '9' | '_' =>
        putChar(ch)
        nextChar()
        getIdentRest()
      case _ =>
        finishNamedToken()

  @tailrec
  final protected def getOperatorRest(): Unit =
    trace(s"getOperatorRest[${offset}]: ch: '${String.valueOf(ch)}'")
    (ch: @switch) match
      case '>' =>
        // Special handling for '>>' to emit two GT tokens instead of one identifier
        if tokenBuffer.last == '>' then
          // We already have a '>' in the buffer; don't consume the next '>'
          // so the next scan picks it up as a separate token.
          finishNamedToken()
        else
          putChar(ch)
          nextChar()
          getOperatorRest()
      case '~' | '!' | '@' | '#' | '$' | '%' | '^' | '+' | '-' | '<' | '?' | ':' | '=' | '&' | '|' |
          '\\' =>
        putChar(ch)
        nextChar()
        getOperatorRest()
      case SU =>
        finishNamedToken()
      case _ =>
        finishNamedToken()

  protected def getNumber(base: Int): Unit =
    while isNumberSeparator(ch) || digit2int(ch, base) >= 0 do
      putChar(ch)
      nextChar()
    checkNoTrailingNumberSeparator()
    var tokenType = tokenTypeInfo.integerLiteral

    if base == 10 && ch == '.' then
      val nextCh = lookAheadChar()
      if '0' <= nextCh && nextCh <= '9' then
        putChar(ch)
        nextChar()
        tokenType = getFraction()
      else
        tokenType = tokenTypeInfo.integerLiteral
    else
      (ch: @switch) match
        case 'e' | 'E' | 'f' | 'F' | 'd' | 'D' =>
          if base == 10 then
            tokenType = getFraction()
        case 'l' | 'L' =>
          nextChar()
          tokenType = tokenTypeInfo.longLiteral
        case _ =>

    checkNoTrailingNumberSeparator()

    flushTokenString()
    current.token = tokenType
  end getNumber

  protected def getFraction(): Token =
    var tokenType = tokenTypeInfo.decimalLiteral
    trace(s"getFraction ch[${offset}]: '${ch}'")
    while '0' <= ch && ch <= '9' || isNumberSeparator(ch) do
      putChar(ch)
      nextChar()
    checkNoTrailingNumberSeparator()
    if ch == 'e' || ch == 'E' then
      putChar(ch)
      nextChar()
      if ch == '+' || ch == '-' then
        putChar(ch)
        nextChar()
      if '0' <= ch && ch <= '9' || isNumberSeparator(ch) then
        putChar(ch)
        nextChar()
        while '0' <= ch && ch <= '9' || isNumberSeparator(ch) do
          putChar(ch)
          nextChar()
        checkNoTrailingNumberSeparator()
      tokenType = tokenTypeInfo.expLiteral
    if ch == 'd' || ch == 'D' then
      putChar(ch)
      nextChar()
      tokenType = tokenTypeInfo.doubleLiteral
    else if ch == 'f' || ch == 'F' then
      putChar(ch)
      nextChar()
      tokenType = tokenTypeInfo.floatLiteral
    tokenType
  end getFraction

  protected def getLineComment(): Unit =
    @tailrec
    def readToLineEnd(): Unit =
      putChar(ch)
      nextChar()
      if (ch != CR) && (ch != LF) && (ch != SU) then
        readToLineEnd()

    if (ch != CR) && (ch != LF) && (ch != SU) then
      readToLineEnd()
    val commentLine = flushTokenString()
    current.token = tokenTypeInfo.commentToken
    current.str = commentLine
    pushComment()

  protected def getDocComment(): Unit =
    @tailrec
    def readToTripleHyphen(): Unit =
      putChar(ch)
      nextRawChar()
      if ch == '-' then
        putChar(ch)
        nextRawChar()
        if ch == '-' then
          putChar(ch)
          nextRawChar()
          if ch == '-' then
            putChar(ch)
            nextRawChar()
          else
            readToTripleHyphen()
        else
          readToTripleHyphen()
      else if ch != SU then
        readToTripleHyphen()

    readToTripleHyphen()
    val commentDoc = flushTokenString()
    current.token = tokenTypeInfo.docCommentToken
    current.str = commentDoc
    pushComment()

  protected def getSingleQuoteString(): Unit =
    consume('\'')

    @tailrec
    def readStringContent(): Unit =
      ch match
        case '\'' =>
          nextChar()
          ch match
            case '\'' =>
              // '' escape sequence
              putChar('\'')
              nextChar()
              readStringContent()
            case _ =>
              current.token = tokenTypeInfo.singleQuoteString
              current.str = flushTokenString()
        case SU =>
          consume('\'')
        case _ =>
          putChar(ch)
          nextChar()
          readStringContent()

    readStringContent()

  protected def getDoubleQuoteString(resultingToken: Token): Unit =
    // Unicode / escape sequences are not decoded here; subclasses can override
    // getStringLiteral to support them.
    nextChar()
    if ch == '\"' then
      nextChar()
      if ch == '\"' then
        nextRawChar()
        getRawStringLiteral(resultingToken)
      else
        current.token = resultingToken
        current.str = ""
    else
      getStringLiteral()

  protected def getBackQuoteString(): Unit =
    consume('`')
    while ch != '`' && ch != SU do
      putChar(ch)
      nextChar()
    consume('`')
    current.token = tokenTypeInfo.backQuotedIdentifier
    current.str = flushTokenString()

  protected def getStringLiteral(): Unit =
    while ch != '"' && ch != SU do
      putChar(ch)
      nextChar()
    consume('"')
    current.token = tokenTypeInfo.doubleQuoteString
    current.str = flushTokenString()

  @tailrec
  private def getRawStringLiteral(resultingToken: Token): Unit =
    if ch == '\"' then
      nextRawChar()
      if isTripleQuote() then
        flushTokenString()
        current.token = tokenTypeInfo.tripleQuoteString
      else
        getRawStringLiteral(resultingToken)
    else if ch == SU then
      reportError("Unclosed multi-line string literal", offset)
    else
      putChar(ch)
      nextRawChar()
      getRawStringLiteral(resultingToken)

  protected def isTripleQuote(): Boolean =
    if ch == '"' then
      nextRawChar()
      if ch == '"' then
        nextRawChar()
        while ch == '"' do
          putChar('"')
          nextChar()
        true
      else
        putChar('"')
        putChar('"')
        false
    else
      putChar('"')
      false

  protected def getBlockComment(): Unit =
    @tailrec
    def readToCommentEnd(): Unit =
      if ch == SU then
        reportError("unclosed block comment", offset)
      else
        putChar(ch)
        nextChar()
        if ch == '*' then
          putChar(ch)
          nextChar()
          if ch == '/' then
            putChar(ch)
            nextChar()
          else
            readToCommentEnd()
        else
          readToCommentEnd()

    readToCommentEnd()
    current.token = tokenTypeInfo.commentToken
    current.str = flushTokenString()
    pushComment()

end ScannerBase

object Scanner:
  sealed trait Region:
    def outer: Region

  // Inside an interpolated string
  case class InString(multiline: Boolean, outer: Region) extends Region
  case class InBackquoteString(outer: Region)            extends Region
  case class InBraces(outer: Region)                     extends Region

  // Inside an indented region
  case class Indented(level: Int, outer: Region | Null) extends Region
