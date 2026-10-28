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
package wvlet.uni.text.yaml

import wvlet.uni.json.JSON.*
import wvlet.uni.text.Span
import wvlet.uni.text.parser.LineReader
import wvlet.uni.text.parser.Tokens

import scala.collection.mutable

/**
  * A recursive-descent YAML 1.2 parser that builds [[YAMLNode]] trees with their source text.
  * [[YAMLResolver]] turns a tree into [[JSONValue]]s.
  *
  * Block structure is tracked by columns: each block collection knows its own column, and a nested
  * node must start at a deeper column than its parent (`parentIndent`). The top-level node has
  * `parentIndent = -1`.
  *
  * Nodes are first built as [[YAMLParser.PNode]]s holding offsets in the normalized text. Once a
  * document is complete, [[build]] slices the source text of each node and the trivia between them.
  */
private[yaml] class YAMLParser(input: String):
  import YAMLParser.*
  import PNode.*

  // Drop a byte order mark so that a leading `---` stays at column 0
  private val bom =
    if input.startsWith("\uFEFF") then
      1
    else
      0

  private val r    = LineReader(input.substring(bom))
  val text: String = r.text

  // The end offset of the last plain scalar scanned
  private var plainEnd = 0
  // The end offset of the last properties (anchor and tag) read
  private var propsEnd = 0

  /**
    * Parse every document of the stream. Each node gets its source text, but not the trivia around
    * the root.
    */
  def parseStream(): Seq[YAMLNode] = parseRoots().map(build(_, -1))

  /**
    * Parse a stream with at most one document, keeping all of its text
    */
  def parseDocument(): YAMLDocument =
    parseRoots() match
      case Seq() =>
        val root = PScalar("", ScalarStyle.Plain, None, None, text.length, text.length)
        document(root)
      case Seq(root) =>
        document(root)
      case docs =>
        throw IllegalArgumentException(
          s"Expected a single YAML document, but found ${docs.size}. Use YAML.parseAll instead"
        )

  private def document(root: PNode): YAMLDocument =
    val node = build(root, root.start)
    YAMLDocument(
      node,
      YAMLSource(
        input,
        input.substring(0, rawOffset(root.start)),
        input.substring(rawOffset(root.cend)),
        "",
        -1,
        Span.within(0, input.length),
        Tuple1(node),
        0
      )
    )

  private def parseRoots(): Seq[PNode] =
    val docs     = Seq.newBuilder[PNode]
    var continue = true
    while continue do
      skipToContent()
      // Skip directives (%YAML, %TAG). Tag handles are not resolved
      while r.column == 0 && r.peek == '%' do
        r.nextLine()
        skipToContent()
      if r.isEOF then
        continue = false
      else if atDocumentMarker("...") then
        r.advance(3)
      else
        if atDocumentMarker("---") then
          r.advance(3)
        skipToContent()
        val doc =
          if r.isEOF || atAnyDocumentMarker then
            PScalar("", ScalarStyle.Plain, None, None, r.offset, r.offset)
          else
            withComment(parseBlockNode(-1, allowCollection = true))
        docs += doc
        skipToContent()
        if atDocumentMarker("...") then
          r.advance(3)
        else if !r.isEOF && !atDocumentMarker("---") then
          r.error("Unexpected content after the end of a node")
    docs.result()

  end parseRoots

  // ---- Block nodes ----

  /**
    * Parse a block node, including its anchor and tag properties
    *
    * @param allowCollection
    *   false for a mapping value on the key's line, where an implicit mapping or a sequence entry
    *   cannot start
    * @param compactSequence
    *   allow a sequence at `parentIndent` (a sequence as a mapping value at the key's column)
    */
  private def parseBlockNode(
      parentIndent: Int,
      allowCollection: Boolean,
      compactSequence: Boolean = false
  ): PNode =
    val nodeColumn    = r.column
    val start         = r.offset
    val (anchor, tag) = parseProperties()
    if (anchor.isDefined || tag.isDefined) && atLineEndOrComment then
      // Properties on their own line apply to the node below
      val end = propsEnd
      skipToContent()
      if isNodeStart(parentIndent, compactSequence) then
        parseBlockContent(r.column, parentIndent, None, None, allowCollection = true, r.offset)
          .withProps(anchor, tag, start)
      else
        PScalar("", ScalarStyle.Plain, anchor, tag, start, end)
    else
      parseBlockContent(nodeColumn, parentIndent, anchor, tag, allowCollection, start)

  /**
    * Is the next content a node nested under `parentIndent`?
    */
  private def isNodeStart(parentIndent: Int, compactSequence: Boolean): Boolean =
    !r.isEOF && !atAnyDocumentMarker &&
      (r.column > parentIndent || compactSequence && r.column == parentIndent && isSequenceEntry)

  /**
    * Parse a block node's content. Properties on the same line as an implicit mapping key (`&a key:
    * v`) apply to the key.
    *
    * @param c
    *   the node's column, before any properties. A block collection starting here uses it
    * @param propsStart
    *   the offset of the properties, or of the content when there are none
    */
  private def parseBlockContent(
      c: Int,
      parentIndent: Int,
      anchor: Option[String],
      tag: Option[String],
      allowCollection: Boolean,
      propsStart: Int
  ): PNode =
    val start                      = r.offset
    def mapping(key: PNode): PNode =
      if !allowCollection then
        r.error("A mapping is not allowed here")
      parseBlockMapping(c, key.withProps(anchor, tag, propsStart))

    val node =
      r.peek match
        case '-' if isSequenceEntry =>
          if !allowCollection then
            r.error("A sequence entry is not allowed here")
          parseBlockSequence(c)
        case '|' | '>' =>
          parseBlockScalar(parentIndent)
        case '?' if isSeparator(r.lookAhead(1)) =>
          r.error("Explicit mapping keys (?) are not supported")
        case '[' | '{' =>
          val v = parseFlowCollection()
          r.skipWhiteSpaces()
          if isMappingIndicator then
            r.error("Complex (non-scalar) mapping keys are not supported", start)
          v
        case '*' =>
          val v = parseAlias()
          r.skipWhiteSpaces()
          if isMappingIndicator then
            return mapping(v)
          v
        case '"' | '\'' =>
          val v = parseQuoted(start)
          r.skipWhiteSpaces()
          if isMappingIndicator then
            return mapping(v)
          v
        case _ =>
          checkPlainStart()
          val first    = scanPlainLine(flow = false)
          val firstEnd = plainEnd
          if isMappingIndicator then
            return mapping(PScalar(first, ScalarStyle.Plain, None, None, start, firstEnd))
          val s = continuePlain(first, parentIndent, flow = false)
          PScalar(s, ScalarStyle.Plain, None, None, start, plainEnd)
      end match
    end node
    node.withProps(anchor, tag, propsStart)

  end parseBlockContent

  /**
    * Parse a block mapping at column `c`. The reader is at the ':' of the first key.
    */
  private def parseBlockMapping(c: Int, firstKey: PNode): PNode =
    val entries  = mutable.ArrayBuffer.empty[(PNode, PNode)]
    var key      = firstKey
    var continue = true
    while continue do
      r.advance() // ':'
      val colonEnd = r.offset
      r.skipWhiteSpaces()
      val value =
        if atLineEndOrComment then
          skipToContent()
          if isNodeStart(c, compactSequence = true) then
            parseBlockNode(c, allowCollection = true, compactSequence = true)
          else
            emptyAt(colonEnd)
        else
          parseBlockNode(c, allowCollection = false, compactSequence = true)
      entries += key -> withComment(value)
      skipToContent()
      if r.isEOF || atAnyDocumentMarker || r.column < c then
        continue = false
      else if r.column > c then
        r.error("Bad indentation of a mapping entry")
      else
        key = parseMappingKey()
    PMap(entries.toSeq, false, None, None, firstKey.start, firstKey.start, entries.last._2.cend, c)

  end parseBlockMapping

  /**
    * Parse a mapping key at the current position and stop at the following ':'
    */
  private def parseMappingKey(): PNode =
    val start         = r.offset
    val (anchor, tag) = parseProperties()
    val contentStart  = r.offset
    val key           =
      r.peek match
        case '-' if isSequenceEntry =>
          r.error("Expected a mapping key, but found a sequence entry")
        case '?' if isSeparator(r.lookAhead(1)) =>
          r.error("Explicit mapping keys (?) are not supported")
        case '[' | '{' =>
          r.error("Complex (non-scalar) mapping keys are not supported")
        case '*' =>
          parseAlias()
        case '"' | '\'' =>
          parseQuoted(contentStart)
        case _ =>
          checkPlainStart()
          val s = scanPlainLine(flow = false)
          PScalar(s, ScalarStyle.Plain, None, None, contentStart, plainEnd)
    r.skipWhiteSpaces()
    if !isMappingIndicator then
      r.error("Expected ':' after a mapping key")
    key.withProps(anchor, tag, start)

  private def parseBlockSequence(c: Int): PNode =
    val start    = r.offset
    val items    = mutable.ArrayBuffer.empty[PNode]
    var continue = true
    while continue do
      r.advance() // '-'
      val dashEnd = r.offset
      r.skipWhiteSpaces()
      val item =
        if atLineEndOrComment then
          skipToContent()
          if isNodeStart(c, compactSequence = false) then
            parseBlockNode(c, allowCollection = true)
          else
            emptyAt(dashEnd)
        else
          parseBlockNode(c, allowCollection = true)
      items += withComment(item)
      skipToContent()
      if r.isEOF || atAnyDocumentMarker || r.column < c then
        continue = false
      else if r.column > c then
        r.error("Bad indentation of a sequence entry")
      else if !isSequenceEntry then
        // A sequence used as a mapping value at the key's column ends here
        continue = false
    PSeq(items.toSeq, false, None, None, start, start, items.last.cend, c)

  end parseBlockSequence

  /**
    * Parse a literal (|) or folded (>) block scalar
    */
  private def parseBlockScalar(parentIndent: Int): PNode =
    val start          = r.offset
    val folded         = r.peek == '>'
    var chomping       = 'c' // c: clip, s: strip, k: keep
    var explicitIndent = 0
    r.advance()
    var i = 0
    while i < 2 do
      r.peek match
        case '-' =>
          chomping = 's'
          r.advance()
        case '+' =>
          chomping = 'k'
          r.advance()
        case d if d >= '1' && d <= '9' =>
          explicitIndent = d - '0'
          r.advance()
        case _ =>
      i += 1
    r.skipWhiteSpaces()
    if r.peek == '#' then
      r.readToLineEnd()
    if !r.isAtLineEnd then
      r.error("Unexpected text after a block scalar header")
    r.skipLineBreak()

    // (line content after the indentation, or null for an empty line; followed by a line break?)
    val lines  = mutable.ArrayBuffer.empty[(String | Null, Boolean)]
    var indent =
      if explicitIndent > 0 then
        (parentIndent + explicitIndent).max(0)
      else
        -1
    var continue = !r.isEOF
    while continue do
      val m      = r.mark
      val spaces = r.skipSpaces()
      if r.isAtLineEnd && (indent < 0 || spaces <= indent) then
        // An empty line
        val hasBreak = r.skipLineBreak()
        lines += ((null, hasBreak))
        continue = hasBreak && !r.isEOF
      else
        if indent < 0 then
          indent = spaces
        if spaces < indent || indent <= parentIndent || spaces == 0 && atAnyDocumentMarker then
          r.reset(m)
          continue = false
        else
          r.reset(m)
          r.advance(indent)
          val line     = r.readToLineEnd()
          val hasBreak = r.skipLineBreak()
          lines += ((line, hasBreak))
          continue = hasBreak && !r.isEOF
    end while

    // Trailing empty lines that ended at a less-indented line belong to the next node
    // but still count for the keep (+) chomping, as YAML specifies.
    val lastContent = lines.lastIndexWhere(_._1 != null)
    val body        =
      if lastContent < 0 then
        ""
      else
        val content = lines.take(lastContent + 1).map(x => Option(x._1).getOrElse(""))
        if folded then
          foldLines(content)
        else
          content.mkString("\n")
    val trailingBreaks = lines.drop(lastContent.max(0)).count(_._2)
    val chomped        =
      chomping match
        case 's' =>
          body
        case 'k' =>
          body + "\n" * trailingBreaks
        case _ =>
          if lastContent >= 0 && lines(lastContent)._2 then
            body + "\n"
          else
            body
    // The line break after the last line belongs to the trivia that follows
    var end = r.offset
    while end > start && text.charAt(end - 1) == '\n' do
      end -= 1
    val style =
      if folded then
        ScalarStyle.Folded
      else
        ScalarStyle.Literal
    PScalar(chomped, style, None, None, start, end)

  end parseBlockScalar

  /**
    * Fold lines of a folded (>) block scalar: line breaks between normal lines become spaces, and
    * lines starting with whitespace (more-indented lines) keep their line breaks.
    */
  private def foldLines(lines: Iterable[String]): String =
    val sb             = StringBuilder()
    var seenContent    = false
    var prevNormal     = false
    var pendingEmpties = 0
    for line <- lines do
      if line.isEmpty then
        pendingEmpties += 1
      else
        val normal = !(line.charAt(0) == ' ' || line.charAt(0) == '\t')
        if !seenContent then
          sb.append("\n" * pendingEmpties)
        else if prevNormal && normal then
          if pendingEmpties == 0 then
            sb.append(' ')
          else
            sb.append("\n" * pendingEmpties)
        else
          sb.append("\n" * (pendingEmpties + 1))
        sb.append(line)
        seenContent = true
        prevNormal = normal
        pendingEmpties = 0
    sb.result()

  // ---- Flow nodes ----

  private def parseFlowCollection(): PNode =
    if r.peek == '[' then
      parseFlowSequence()
    else
      parseFlowMapping()

  private def parseFlowSequence(): PNode =
    val start    = r.offset
    val items    = mutable.ArrayBuffer.empty[PNode]
    var continue = true
    r.advance() // '['
    while continue do
      skipFlowSpace()
      r.peek match
        case ']' =>
          r.advance()
          continue = false
        case _ if r.isEOF =>
          r.error("Unclosed flow sequence", start)
        case _ =>
          val entryStart = r.offset
          val node       = parseFlowNode()
          skipFlowSpace()
          if r.peek == ':' && (node.adjacentKey || isFlowSeparator(r.lookAhead(1))) then
            // A single-pair mapping: [a: 1, b: 2]
            flowKey(node.node, entryStart)
            r.advance()
            val colonEnd = r.offset
            skipFlowSpace()
            val value =
              if r.peek == ',' || r.peek == ']' then
                emptyAt(colonEnd)
              else
                parseFlowNode().node
            items +=
              PMap(Seq(node.node -> value), true, None, None, entryStart, entryStart, value.end, -1)
            skipFlowSpace()
          else
            items += node.node
          expectFlowSeparator(']', start)
    PSeq(items.toSeq, true, None, None, start, start + 1, r.offset, -1)

  end parseFlowSequence

  private def parseFlowMapping(): PNode =
    val start    = r.offset
    val entries  = mutable.ArrayBuffer.empty[(PNode, PNode)]
    var continue = true
    r.advance() // '{'
    while continue do
      skipFlowSpace()
      r.peek match
        case '}' =>
          r.advance()
          continue = false
        case _ if r.isEOF =>
          r.error("Unclosed flow mapping", start)
        case '?' if isSeparator(r.lookAhead(1)) =>
          r.error("Explicit mapping keys (?) are not supported")
        case _ =>
          val keyOffset = r.offset
          val key       = parseFlowNode().node
          flowKey(key, keyOffset)
          skipFlowSpace()
          val value =
            if r.peek == ':' then
              r.advance()
              val colonEnd = r.offset
              skipFlowSpace()
              if r.peek == ',' || r.peek == '}' then
                emptyAt(colonEnd)
              else
                parseFlowNode().node
            else
              emptyAt(key.end)
          entries += key -> value
          skipFlowSpace()
          expectFlowSeparator('}', start)
    PMap(entries.toSeq, true, None, None, start, start + 1, r.offset, -1)

  end parseFlowMapping

  private def flowKey(key: PNode, offset: Int): Unit =
    key match
      case _: PMap | _: PSeq =>
        r.error("Complex (non-scalar) mapping keys are not supported", offset)
      case _ =>

  private def expectFlowSeparator(close: Char, start: Int): Unit =
    if r.isEOF then
      r.error(
        if close == ']' then
          "Unclosed flow sequence"
        else
          "Unclosed flow mapping"
        ,
        start
      )
    else if r.peek == ',' then
      r.advance()
    else if r.peek != close then
      r.error(s"Expected ',' or '${close}'")

  private def parseFlowNode(): FlowNode =
    val start         = r.offset
    val (anchor, tag) = parseProperties()
    skipFlowSpace()
    val contentStart = r.offset
    r.peek match
      case '[' | '{' =>
        FlowNode(parseFlowCollection().withProps(anchor, tag, start), adjacentKey = true)
      case '*' =>
        FlowNode(parseAlias(), adjacentKey = true)
      case '"' | '\'' =>
        FlowNode(parseQuoted(contentStart).withProps(anchor, tag, start), adjacentKey = true)
      case _ =>
        val node =
          if r.peek == ',' || r.peek == ']' || r.peek == '}' || r.peek == ':' then
            PScalar("", ScalarStyle.Plain, None, None, contentStart, contentStart)
          else
            checkPlainStart()
            val s = continuePlain(scanPlainLine(flow = true), -1, flow = true)
            PScalar(s, ScalarStyle.Plain, None, None, contentStart, plainEnd)
        if anchor.isDefined || tag.isDefined then
          FlowNode(node.withProps(anchor, tag, start), adjacentKey = false)
        else
          FlowNode(node, adjacentKey = false)

  end parseFlowNode

  // ---- Scalars ----

  /**
    * Scan a plain scalar up to the end of the line, a comment, or a ':' indicator. Trailing spaces
    * are not included, and [[plainEnd]] is set to the end of the text.
    */
  private def scanPlainLine(flow: Boolean): String =
    val start    = r.offset
    var end      = start
    var continue = true
    while continue && !r.isAtLineEnd do
      val ch = r.peek
      if ch == ':' && (isSeparator(r.lookAhead(1)) || flow && isFlowIndicator(r.lookAhead(1))) then
        continue = false
      else if ch == '#' && r.offset > start && isWhiteSpace(text.charAt(r.offset - 1)) then
        continue = false
      else if flow && isFlowIndicator(ch) then
        continue = false
      else
        r.advance()
        if !isWhiteSpace(ch) then
          end = r.offset
    plainEnd = end
    text.substring(start, end)

  /**
    * Continue a plain scalar onto the following lines that are indented more than `parentIndent`.
    * Line breaks fold into a space, and empty lines into line breaks.
    */
  private def continuePlain(first: String, parentIndent: Int, flow: Boolean): String =
    val sb       = StringBuilder(first)
    var end      = plainEnd
    var continue = true
    while continue && r.peek == '\n' do
      val m = r.mark
      r.advance()
      var emptyLines = 0
      r.skipWhiteSpaces()
      while r.peek == '\n' do
        emptyLines += 1
        r.advance()
        r.skipWhiteSpaces()
      if r.isEOF || r.column <= parentIndent || r.peek == '#' || atAnyDocumentMarker ||
        flow && isFlowIndicator(r.peek)
      then
        r.reset(m)
        continue = false
      else
        val lineStart = r.offset
        val line      = scanPlainLine(flow)
        if line.isEmpty then
          r.reset(m)
          continue = false
        else
          end = plainEnd
          if emptyLines == 0 then
            sb.append(' ')
          else
            sb.append("\n" * emptyLines)
          sb.append(line)
          if !flow && isMappingIndicator then
            r.error("A mapping is not allowed in a multi-line plain scalar", lineStart)
    plainEnd = end
    sb.result()

  end continuePlain

  private def checkPlainStart(): Unit =
    val ch = r.peek
    if "@`%|>,[]{}#&*!\"'".indexOf(ch) >= 0 ||
      (ch == '-' || ch == '?' || ch == ':') && isSeparator(r.lookAhead(1))
    then
      r.error(s"Unexpected character '${ch}'")

  private def parseQuoted(start: Int): PNode =
    val style =
      if r.peek == '"' then
        ScalarStyle.DoubleQuoted
      else
        ScalarStyle.SingleQuoted
    val s = parseQuotedScalar()
    PScalar(s, style, None, None, start, r.offset)

  private def parseQuotedScalar(): String =
    val start   = r.offset
    val quote   = r.peek
    val sb      = StringBuilder()
    var keepLen = 0 // escaped characters before this length are not trimmed when folding
    var closed  = false
    r.advance()
    while !closed do
      if r.isEOF then
        r.error(
          if quote == '"' then
            "Unclosed double-quoted string"
          else
            "Unclosed single-quoted string"
          ,
          start
        )
      val ch = r.peek
      if ch == quote then
        if quote == '\'' && r.lookAhead(1) == '\'' then
          sb.append('\'')
          r.advance(2)
        else
          r.advance()
          closed = true
      else if ch == '\n' then
        // Fold the line break: trim trailing spaces, and turn empty lines into line breaks
        var len = sb.length
        while len > keepLen && isWhiteSpace(sb.charAt(len - 1)) do
          len -= 1
        sb.setLength(len)
        r.advance()
        r.skipWhiteSpaces()
        var emptyLines = 0
        while r.peek == '\n' do
          emptyLines += 1
          r.advance()
          r.skipWhiteSpaces()
        if emptyLines == 0 then
          sb.append(' ')
        else
          sb.append("\n" * emptyLines)
      else if ch == '\\' && quote == '"' then
        r.advance()
        if r.peek == '\n' then
          // An escaped line break joins the lines without a space
          r.advance()
          r.skipWhiteSpaces()
        else
          appendEscape(sb)
          keepLen = sb.length
      else
        sb.append(ch)
        r.advance()
      end if
    end while
    sb.result()

  end parseQuotedScalar

  private def appendEscape(sb: StringBuilder): Unit =
    val escapeStart = r.offset - 1
    val ch          = r.peek
    r.advance()
    ch match
      case '0' =>
        sb.append('\u0000')
      case 'a' =>
        sb.append('\u0007')
      case 'b' =>
        sb.append('\b')
      case 't' | '\t' =>
        sb.append('\t')
      case 'n' =>
        sb.append('\n')
      case 'v' =>
        sb.append('\u000b')
      case 'f' =>
        sb.append('\f')
      case 'r' =>
        sb.append('\r')
      case 'e' =>
        sb.append('\u001b')
      case ' ' | '"' | '/' | '\\' =>
        sb.append(ch)
      case 'N' =>
        sb.append('\u0085')
      case '_' =>
        sb.append('\u00a0')
      case 'L' =>
        sb.append('\u2028')
      case 'P' =>
        sb.append('\u2029')
      case 'x' =>
        appendCodePoint(sb, readHex(2, escapeStart))
      case 'u' =>
        appendCodePoint(sb, readHex(4, escapeStart))
      case 'U' =>
        appendCodePoint(sb, readHex(8, escapeStart))
      case _ =>
        r.error(s"Unknown escape sequence '\\${ch}'", escapeStart)
    end match

  end appendEscape

  private def readHex(digits: Int, escapeStart: Int): Int =
    var v = 0
    var i = 0
    while i < digits do
      val d = Tokens.digit2int(r.peek, 16)
      if d < 0 then
        r.error("Invalid hexadecimal escape sequence", escapeStart)
      v = v * 16 + d
      r.advance()
      i += 1
    v

  private def appendCodePoint(sb: StringBuilder, cp: Int): Unit =
    if cp < 0 || cp > Character.MAX_CODE_POINT then
      r.error(s"Invalid code point: ${cp}")
    sb.appendAll(Character.toChars(cp))

  // ---- Anchors and aliases ----

  private def parseProperties(): (Option[String], Option[String]) =
    var anchor: Option[String] = None
    var tag: Option[String]    = None
    propsEnd = r.offset
    while r.peek == '&' || r.peek == '!' do
      val isAnchor = r.peek == '&'
      val start    = r.offset
      if isAnchor then
        r.advance()
      val name = readName()
      if isAnchor then
        if name.isEmpty then
          r.error("An anchor name is missing", start)
        anchor = Some(name)
      else
        tag = Some(name)
      propsEnd = r.offset
      r.skipWhiteSpaces()
    (anchor, tag)

  private def parseAlias(): PNode =
    val start = r.offset
    r.advance() // '*'
    val name = readName()
    PAlias(name, start, r.offset)

  private def readName(): String =
    val start = r.offset
    while !r.isAtLineEnd && !isWhiteSpace(r.peek) && !isFlowIndicator(r.peek) do
      r.advance()
    text.substring(start, r.offset)

  // ---- Helpers ----

  private def emptyAt(offset: Int): PNode = PScalar(
    "",
    ScalarStyle.Plain,
    None,
    None,
    offset,
    offset
  )

  /**
    * Attach a comment on the same line after a node that ends its line
    */
  private def withComment(p: PNode): PNode =
    p match
      case s: PScalar if s.style != ScalarStyle.Literal && s.style != ScalarStyle.Folded =>
        s.copy(commentEnd = lineCommentEnd(s.end))
      case a: PAlias =>
        a.copy(commentEnd = lineCommentEnd(a.end))
      case m: PMap if m.flow =>
        m.copy(commentEnd = lineCommentEnd(m.end))
      case s: PSeq if s.flow =>
        s.copy(commentEnd = lineCommentEnd(s.end))
      case other =>
        other

  private def lineCommentEnd(end: Int): Int =
    var i = end
    while i < text.length && isWhiteSpace(text.charAt(i)) do
      i += 1
    if i < text.length && text.charAt(i) == '#' then
      val e = text.indexOf('\n', i)
      if e < 0 then
        text.length
      else
        e
    else
      end

  /**
    * Skip spaces, comments, and line breaks up to the next content
    */
  private def skipToContent(): Unit =
    var continue = true
    while continue do
      val atLineStart = r.isAtLineStart
      r.skipSpaces()
      if r.peek == '\t' then
        r.skipWhiteSpaces()
        if atLineStart && !r.isAtLineEnd && r.peek != '#' then
          r.error("Tabs are not allowed for indentation")
      if r.peek == '#' then
        r.readToLineEnd()
      if r.peek == '\n' then
        r.advance()
      else
        continue = false

  /**
    * Skip white spaces, comments, and line breaks inside a flow collection
    */
  private def skipFlowSpace(): Unit =
    var continue = true
    while continue do
      r.skipWhiteSpaces()
      if r.peek == '#' then
        r.readToLineEnd()
      if r.peek == '\n' then
        r.advance()
      else
        continue = false

  private def atLineEndOrComment: Boolean = r.isAtLineEnd || r.peek == '#'

  private def isSequenceEntry: Boolean = r.peek == '-' && isSeparator(r.lookAhead(1))

  private def isMappingIndicator: Boolean = r.peek == ':' && isSeparator(r.lookAhead(1))

  private def atDocumentMarker(marker: String): Boolean =
    r.column == 0 && r.startsWith(marker) && isSeparator(r.lookAhead(3))

  private def atAnyDocumentMarker: Boolean = atDocumentMarker("---") || atDocumentMarker("...")

  // ---- Building nodes with their source text ----

  /**
    * Map offsets in the normalized text back to the input, whose line breaks may be `\r\n` or `\r`
    */
  private val rawOffset: Int => Int =
    if input.indexOf('\r') < 0 then
      _ + bom
    else
      val map = Array.ofDim[Int](text.length + 1)
      var i   = 0
      var j   = bom
      while i < text.length do
        map(i) = j
        if input.charAt(j) == '\r' && j + 1 < input.length && input.charAt(j + 1) == '\n' then
          j += 2
        else
          j += 1
        i += 1
      map(text.length) = input.length
      map(_)

  private def raw(start: Int, end: Int): String = input.substring(rawOffset(start), rawOffset(end))

  /**
    * Build the node, with the text since `leadingStart` (-1 for none) as its leading trivia
    */
  private def build(p: PNode, leadingStart: Int): YAMLNode =
    def source(node: YAMLNode, opening: Int, indent: Int, offset: Int = p.start): YAMLNode = node
      .withSource(
        YAMLSource(
          raw(p.start, p.end),
          if leadingStart < 0 then
            ""
          else
            raw(leadingStart, p.start)
          ,
          raw(p.end, p.cend),
          raw(p.start, opening),
          indent,
          Span.within(rawOffset(p.start), rawOffset(p.end)),
          node.content,
          offset
        )
      )
    p match
      case s: PScalar =>
        val offset =
          if s.valueStart < 0 then
            s.start
          else
            s.valueStart
        source(YAMLNode.Scalar(s.value, s.style, s.anchor, s.tag), s.start, -1, offset)
      case a: PAlias =>
        source(YAMLNode.Alias(a.name), a.start, -1)
      case m: PMap =>
        var prev    = m.childStart
        val entries = m
          .entries
          .map { (k, v) =>
            val key   = build(k, prev)
            val value = build(v, k.end)
            prev = v.cend
            YAMLEntry(key, value)
          }
        source(YAMLNode.Mapping(entries, m.flow, m.anchor, m.tag), m.childStart, m.indent)
      case s: PSeq =>
        var prev  = s.childStart
        val items = s
          .items
          .map { i =>
            val item = build(i, prev)
            prev = i.cend
            item
          }
        source(YAMLNode.Sequence(items, s.flow, s.anchor, s.tag), s.childStart, s.indent)
    end match

  end build

end YAMLParser

private[yaml] object YAMLParser:

  /**
    * A node with offsets in the normalized text. `cend` is the end including a comment on the same
    * line (`commentEnd`, or -1 for none).
    */
  enum PNode:
    def start: Int
    def end: Int
    def commentEnd: Int
    def cend: Int =
      if commentEnd < 0 then
        end
      else
        commentEnd

    case PScalar(
        value: String,
        style: ScalarStyle,
        anchor: Option[String],
        tag: Option[String],
        start: Int,
        end: Int,
        commentEnd: Int = -1,
        // The start of the scalar text after its properties, for error messages
        valueStart: Int = -1
    )

    case PMap(
        entries: Seq[(PNode, PNode)],
        flow: Boolean,
        anchor: Option[String],
        tag: Option[String],
        start: Int,
        childStart: Int,
        end: Int,
        indent: Int,
        commentEnd: Int = -1
    )

    case PSeq(
        items: Seq[PNode],
        flow: Boolean,
        anchor: Option[String],
        tag: Option[String],
        start: Int,
        childStart: Int,
        end: Int,
        indent: Int,
        commentEnd: Int = -1
    )

    case PAlias(name: String, start: Int, end: Int, commentEnd: Int = -1)

    /**
      * Add properties (anchor and tag) written before the node at `from`
      */
    def withProps(anchor: Option[String], tag: Option[String], from: Int): PNode =
      this match
        case s: PScalar =>
          s.copy(
            anchor = anchor.orElse(s.anchor),
            tag = tag.orElse(s.tag),
            start = from,
            valueStart =
              if s.valueStart < 0 then
                s.start
              else
                s.valueStart
          )
        case m: PMap =>
          m.copy(anchor = anchor.orElse(m.anchor), tag = tag.orElse(m.tag), start = from)
        case s: PSeq =>
          s.copy(anchor = anchor.orElse(s.anchor), tag = tag.orElse(s.tag), start = from)
        case a: PAlias =>
          // Properties on an alias are not valid YAML, and they are ignored
          a.copy(start = from.min(a.start))

  end PNode

  /**
    * A parsed flow node. `adjacentKey` is true when a following ':' is an indicator even without a
    * space (`"a":1`).
    */
  case class FlowNode(node: PNode, adjacentKey: Boolean)

  def isWhiteSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'

  def isSeparator(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == Tokens.SU

  def isFlowIndicator(ch: Char): Boolean =
    ch == ',' || ch == '[' || ch == ']' || ch == '{' || ch == '}'

  def isFlowSeparator(ch: Char): Boolean = isSeparator(ch) || isFlowIndicator(ch)

  private val intPattern   = "[-+]?[0-9]+".r
  private val octPattern   = "0o([0-7]+)".r
  private val hexPattern   = "0x([0-9a-fA-F]+)".r
  private val floatPattern = "[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?".r
  private val infPattern   = "([-+]?)\\.(inf|Inf|INF)".r

  def isNull(s: String): Boolean =
    s match
      case "" | "~" | "null" | "Null" | "NULL" =>
        true
      case _ =>
        false

  def parseBool(s: String): Option[JSONValue] =
    s match
      case "true" | "True" | "TRUE" =>
        Some(JSONTrue)
      case "false" | "False" | "FALSE" =>
        Some(JSONFalse)
      case _ =>
        None

  def parseInt(s: String): Option[JSONValue] =
    s match
      case intPattern() =>
        s.toLongOption.map(JSONLong(_)).orElse(Some(JSONDouble(s.toDouble)))
      case octPattern(digits) =>
        Some(radixNumber(digits, 8))
      case hexPattern(digits) =>
        Some(radixNumber(digits, 16))
      case _ =>
        None

  private def radixNumber(digits: String, radix: Int): JSONValue =
    val n = BigInt(digits, radix)
    if n.isValidLong then
      JSONLong(n.toLong)
    else
      JSONDouble(n.toDouble)

  def parseFloat(s: String): Option[JSONValue] =
    s match
      case floatPattern(_*) =>
        Some(JSONDouble(s.toDouble))
      case infPattern(sign, _) =>
        Some(
          JSONDouble(
            if sign == "-" then
              Double.NegativeInfinity
            else
              Double.PositiveInfinity
          )
        )
      case ".nan" | ".NaN" | ".NAN" =>
        Some(JSONDouble(Double.NaN))
      case _ =>
        None

  /**
    * Resolve a plain scalar with the YAML 1.2 core schema
    */
  def resolvePlain(s: String): JSONValue =
    if isNull(s) then
      JSONNull()
    else
      parseBool(s).orElse(parseInt(s)).orElse(parseFloat(s)).getOrElse(JSONString(s))

end YAMLParser
