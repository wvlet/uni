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
import wvlet.uni.text.parser.LineReader
import wvlet.uni.text.parser.Tokens

import scala.collection.mutable

/**
  * A recursive-descent YAML 1.2 parser that builds [[JSONValue]]s directly.
  *
  * Block structure is tracked by columns: each block collection knows its own column, and a nested
  * node must start at a deeper column than its parent (`parentIndent`). The top-level node has
  * `parentIndent = -1`.
  */
private[yaml] class YAMLParser(input: String, maxAliasNodes: Long):
  import YAMLParser.*

  private val r    = LineReader(input)
  private val text = r.text

  // anchor name -> (value, the number of nodes the value expands to)
  private val anchors        = mutable.Map.empty[String, (JSONValue, Long)]
  private var nodeCount      = 0L
  private var aliasNodeCount = 0L

  def parseStream(): Seq[JSONValue] =
    val docs = Seq.newBuilder[JSONValue]
    if r.peek == '\uFEFF' then
      r.advance()
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
        anchors.clear()
        skipToContent()
        val doc =
          if r.isEOF || atAnyDocumentMarker then
            JSONNull()
          else
            parseBlockNode(-1, allowCollection = true)
        docs += doc
        skipToContent()
        if atDocumentMarker("...") then
          r.advance(3)
        else if !r.isEOF && !atDocumentMarker("---") then
          r.error("Unexpected content after the end of a node")
    docs.result()

  end parseStream

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
  ): JSONValue =
    val (anchor, tag) = parseProperties()
    val start         = nodeCount
    val value         =
      if (anchor.isDefined || tag.isDefined) && atLineEndOrComment then
        skipToContent()
        if isNodeStart(parentIndent, compactSequence) then
          parseBlockContent(parentIndent, tag, allowCollection = true)
        else
          newNode(resolveScalar("", plain = true, tag, r.offset))
      else
        parseBlockContent(parentIndent, tag, allowCollection)
    anchor.foreach(a => anchors(a) = (value, (nodeCount - start).max(1)))
    value

  /**
    * Is the next content a node nested under `parentIndent`?
    */
  private def isNodeStart(parentIndent: Int, compactSequence: Boolean): Boolean =
    !r.isEOF && !atAnyDocumentMarker &&
      (r.column > parentIndent || compactSequence && r.column == parentIndent && isSequenceEntry)

  private def parseBlockContent(
      parentIndent: Int,
      tag: Option[String],
      allowCollection: Boolean
  ): JSONValue =
    val c     = r.column
    val start = r.offset
    r.peek match
      case '-' if isSequenceEntry =>
        if !allowCollection then
          r.error("A sequence entry is not allowed here")
        parseBlockSequence(c)
      case '|' | '>' =>
        parseBlockScalar(parentIndent, tag)
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
          if !allowCollection then
            r.error("A mapping is not allowed here")
          parseBlockMapping(c, keyText(v, start), start)
        else
          v
      case '"' | '\'' =>
        val s = parseQuotedScalar()
        r.skipWhiteSpaces()
        if isMappingIndicator then
          if !allowCollection then
            r.error("A mapping is not allowed here")
          parseBlockMapping(c, s, start)
        else
          newNode(resolveScalar(s, plain = false, tag, start))
      case _ =>
        checkPlainStart()
        val first = scanPlainLine(flow = false)
        if isMappingIndicator then
          if !allowCollection then
            r.error("A mapping is not allowed here")
          parseBlockMapping(c, first, start)
        else
          val s = continuePlain(first, parentIndent, flow = false)
          newNode(resolveScalar(s, plain = true, tag, start))
    end match

  end parseBlockContent

  /**
    * Parse a block mapping at column `c`. The reader is at the ':' of the first key.
    */
  private def parseBlockMapping(c: Int, firstKey: String, firstKeyOffset: Int): JSONValue =
    nodeCount += 1
    val builder   = MappingBuilder()
    var key       = firstKey
    var keyOffset = firstKeyOffset
    var continue  = true
    while continue do
      r.advance() // ':'
      r.skipWhiteSpaces()
      val value =
        if atLineEndOrComment then
          skipToContent()
          if isNodeStart(c, compactSequence = true) then
            parseBlockNode(c, allowCollection = true, compactSequence = true)
          else
            newNode(JSONNull())
        else
          parseBlockNode(c, allowCollection = false, compactSequence = true)
      builder.add(key, value, keyOffset)
      skipToContent()
      if r.isEOF || atAnyDocumentMarker || r.column < c then
        continue = false
      else if r.column > c then
        r.error("Bad indentation of a mapping entry")
      else
        keyOffset = r.offset
        key = parseMappingKey()
    builder.result()

  end parseBlockMapping

  /**
    * Parse a mapping key at the current position and stop at the following ':'
    */
  private def parseMappingKey(): String =
    val start = r.offset
    parseProperties()
    val key =
      r.peek match
        case '-' if isSequenceEntry =>
          r.error("Expected a mapping key, but found a sequence entry")
        case '?' if isSeparator(r.lookAhead(1)) =>
          r.error("Explicit mapping keys (?) are not supported")
        case '[' | '{' =>
          r.error("Complex (non-scalar) mapping keys are not supported")
        case '*' =>
          keyText(parseAlias(), start)
        case '"' | '\'' =>
          parseQuotedScalar()
        case _ =>
          checkPlainStart()
          scanPlainLine(flow = false)
    r.skipWhiteSpaces()
    if !isMappingIndicator then
      r.error("Expected ':' after a mapping key")
    key

  private def parseBlockSequence(c: Int): JSONValue =
    nodeCount += 1
    val items    = IndexedSeq.newBuilder[JSONValue]
    var continue = true
    while continue do
      r.advance() // '-'
      r.skipWhiteSpaces()
      val item =
        if atLineEndOrComment then
          skipToContent()
          if isNodeStart(c, compactSequence = false) then
            parseBlockNode(c, allowCollection = true)
          else
            newNode(JSONNull())
        else
          parseBlockNode(c, allowCollection = true)
      items += item
      skipToContent()
      if r.isEOF || atAnyDocumentMarker || r.column < c then
        continue = false
      else if r.column > c then
        r.error("Bad indentation of a sequence entry")
      else if !isSequenceEntry then
        // A sequence used as a mapping value at the key's column ends here
        continue = false
    JSONArray(items.result())

  end parseBlockSequence

  /**
    * Parse a literal (|) or folded (>) block scalar
    */
  private def parseBlockScalar(parentIndent: Int, tag: Option[String]): JSONValue =
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
    newNode(resolveScalar(chomped, plain = false, tag, start))

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

  private def parseFlowCollection(): JSONValue =
    if r.peek == '[' then
      parseFlowSequence()
    else
      parseFlowMapping()

  private def parseFlowSequence(): JSONValue =
    nodeCount += 1
    val start    = r.offset
    val items    = IndexedSeq.newBuilder[JSONValue]
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
            r.advance()
            skipFlowSpace()
            val value =
              if r.peek == ',' || r.peek == ']' then
                newNode(JSONNull())
              else
                parseFlowNode().value
            val builder = MappingBuilder()
            builder.add(node.keyText(entryStart, r), value, entryStart)
            nodeCount += 1
            items += builder.result()
            skipFlowSpace()
          else
            items += node.value
          expectFlowSeparator(']', start)
    JSONArray(items.result())

  end parseFlowSequence

  private def parseFlowMapping(): JSONValue =
    nodeCount += 1
    val start    = r.offset
    val builder  = MappingBuilder()
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
          val key       = parseFlowNode().keyText(keyOffset, r)
          skipFlowSpace()
          val value =
            if r.peek == ':' then
              r.advance()
              skipFlowSpace()
              if r.peek == ',' || r.peek == '}' then
                newNode(JSONNull())
              else
                parseFlowNode().value
            else
              newNode(JSONNull())
          builder.add(key, value, keyOffset)
          skipFlowSpace()
          expectFlowSeparator('}', start)
    builder.result()

  end parseFlowMapping

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
    val countStart = nodeCount
    val node       =
      r.peek match
        case '[' | '{' =>
          FlowNode(parseFlowCollection(), None, adjacentKey = true)
        case '*' =>
          val v = parseAlias()
          FlowNode(v, scalarKey(v), adjacentKey = true)
        case '"' | '\'' =>
          val s = parseQuotedScalar()
          FlowNode(
            newNode(resolveScalar(s, plain = false, tag, start)),
            Some(s),
            adjacentKey = true
          )
        case _ =>
          val s =
            if r.peek == ',' || r.peek == ']' || r.peek == '}' || r.peek == ':' then
              ""
            else
              checkPlainStart()
              continuePlain(scanPlainLine(flow = true), -1, flow = true)
          FlowNode(
            newNode(resolveScalar(s, plain = true, tag, start)),
            Some(s),
            adjacentKey = false
          )
    anchor.foreach(a => anchors(a) = (node.value, (nodeCount - countStart).max(1)))
    node

  end parseFlowNode

  // ---- Scalars ----

  /**
    * Scan a plain scalar up to the end of the line, a comment, or a ':' indicator. Trailing spaces
    * are not included.
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
    text.substring(start, end)

  /**
    * Continue a plain scalar onto the following lines that are indented more than `parentIndent`.
    * Line breaks fold into a space, and empty lines into line breaks.
    */
  private def continuePlain(first: String, parentIndent: Int, flow: Boolean): String =
    val sb       = StringBuilder(first)
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
          if emptyLines == 0 then
            sb.append(' ')
          else
            sb.append("\n" * emptyLines)
          sb.append(line)
          if !flow && isMappingIndicator then
            r.error("A mapping is not allowed in a multi-line plain scalar", lineStart)
    sb.result()

  end continuePlain

  private def checkPlainStart(): Unit =
    val ch = r.peek
    if "@`%|>,[]{}#&*!\"'".indexOf(ch) >= 0 ||
      (ch == '-' || ch == '?' || ch == ':') && isSeparator(r.lookAhead(1))
    then
      r.error(s"Unexpected character '${ch}'")

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
        sb.append(' ')
      case 'L' =>
        sb.append(' ')
      case 'P' =>
        sb.append(' ')
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

  /**
    * Resolve a scalar to a JSON value with the YAML 1.2 core schema, or with the given tag
    */
  private def resolveScalar(
      s: String,
      plain: Boolean,
      tag: Option[String],
      offset: Int
  ): JSONValue =
    def fail(kind: String): Nothing = r.error(s"Invalid ${kind} value: ${s}", offset)
    tag match
      case Some("!!str") | Some("!") =>
        JSONString(s)
      case Some("!!int") =>
        parseInt(s).getOrElse(fail("integer"))
      case Some("!!float") =>
        parseFloat(s)
          .orElse(
            parseInt(s).collect { case JSONLong(v) =>
              JSONDouble(v.toDouble)
            }
          )
          .getOrElse(fail("float"))
      case Some("!!bool") =>
        parseBool(s).getOrElse(fail("boolean"))
      case Some("!!null") =>
        if isNull(s) then
          JSONNull()
        else
          fail("null")
      case _ if plain =>
        resolvePlain(s)
      case _ =>
        JSONString(s)

  end resolveScalar

  // ---- Anchors and aliases ----

  private def parseProperties(): (Option[String], Option[String]) =
    var anchor: Option[String] = None
    var tag: Option[String]    = None
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
      r.skipWhiteSpaces()
    (anchor, tag)

  private def parseAlias(): JSONValue =
    val start = r.offset
    r.advance() // '*'
    val name = readName()
    anchors.get(name) match
      case Some((v, size)) =>
        aliasNodeCount += size
        nodeCount += size
        if aliasNodeCount > maxAliasNodes then
          r.error(s"Aliases expand to more than ${maxAliasNodes} nodes", start)
        v
      case None =>
        r.error(s"Unknown alias: *${name}", start)

  private def readName(): String =
    val start = r.offset
    while !r.isAtLineEnd && !isWhiteSpace(r.peek) && !isFlowIndicator(r.peek) do
      r.advance()
    text.substring(start, r.offset)

  // ---- Helpers ----

  private def newNode(v: JSONValue): JSONValue =
    nodeCount += 1
    v

  private def scalarKey(v: JSONValue): Option[String] =
    v match
      case JSONString(s) =>
        Some(s)
      case _: JSONObject | _: JSONArray =>
        None
      case other =>
        Some(other.toJSON)

  private def keyText(v: JSONValue, offset: Int): String = scalarKey(v).getOrElse(
    r.error("Complex (non-scalar) mapping keys are not supported", offset)
  )

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

  /**
    * Accumulates mapping entries, rejecting duplicate keys and applying `<<` merge keys
    */
  private class MappingBuilder:
    private val entries = mutable.ArrayBuffer.empty[(String, JSONValue)]
    private val keys    = mutable.Set.empty[String]
    private val merges  = mutable.ArrayBuffer.empty[JSONObject]

    def add(key: String, value: JSONValue, keyOffset: Int): Unit =
      if key == "<<" then
        value match
          case o: JSONObject =>
            merges += o
          case JSONArray(xs) if xs.forall(_.isInstanceOf[JSONObject]) =>
            merges ++=
              xs.collect { case o: JSONObject =>
                o
              }
          case _ =>
            r.error("A merge key (<<) value must be a mapping or a sequence of mappings", keyOffset)
      else
        if keys.contains(key) then
          r.error(s"Duplicate mapping key: ${key}", keyOffset)
        keys += key
        entries += key -> value

    def result(): JSONObject =
      for
        m      <- merges
        (k, v) <- m.v
        if !keys.contains(k)
      do
        keys += k
        entries += k -> v
      JSONObject(entries.toSeq)

  end MappingBuilder

end YAMLParser

private[yaml] object YAMLParser:

  /**
    * A parsed flow node. `rawKey` keeps the unresolved scalar text for use as a mapping key.
    * `adjacentKey` is true when a following ':' is an indicator even without a space (`"a":1`).
    */
  case class FlowNode(value: JSONValue, rawKey: Option[String], adjacentKey: Boolean):
    def keyText(offset: Int, r: LineReader): String = rawKey.getOrElse(
      r.error("Complex (non-scalar) mapping keys are not supported", offset)
    )

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
