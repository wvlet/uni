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
package wvlet.uni.text.markdown

import wvlet.uni.text.Span
import wvlet.uni.json.JSON.*
import wvlet.uni.text.parser.LineReader
import wvlet.uni.text.yaml.{Frontmatter, YAML}

import scala.collection.mutable

private[markdown] enum BlockKind:
  case Document,
    BlockQuote,
    ListKind,
    Item,
    Paragraph,
    Heading,
    ThematicBreakKind,
    Code,
    Html,
    TableKind

/**
  * A block under construction. The parser keeps the open blocks as a chain of last children from
  * the document root.
  */
private[markdown] final class BlockNode(val kind: BlockKind, var startLine: Int, var startCol: Int):
  var parent: BlockNode | Null = null
  var depth                    = 0
  val children                 = mutable.ArrayBuffer.empty[BlockNode]
  var open                     = true
  var lastLineBlank            = false
  var lastLineChecked          = false
  var endLine                  = startLine
  var endCol                   = 0
  val content                  = StringBuilder()

  // List and list item
  var listData: ListData | Null = null
  // Heading
  var level = 0
  // Code block
  var fenced                      = false
  var fenceChar                   = ' '
  var fenceLength                 = 0
  var fenceOffset                 = 0
  var info                        = ""
  var literal                     = ""
  var htmlBlockType               = 0
  var tableHeader                 = ""
  var tableAlignments             = Seq.empty[TableAlignment]
  val tableRows                   = mutable.ArrayBuffer.empty[String]
  def lastChild: BlockNode | Null =
    if children.isEmpty then
      null
    else
      children.last

  def append(child: BlockNode): Unit =
    child.parent = this
    child.depth = depth + 1
    children += child

  def remove(child: BlockNode): Unit = children -= child

end BlockNode

private[markdown] final case class ListData(
    ordered: Boolean,
    bulletChar: Char,
    start: Int,
    delimiter: Char,
    var padding: Int,
    markerOffset: Int,
    var tight: Boolean = true
)

/**
  * Parses the block structure of a Markdown document, following the CommonMark reference algorithm:
  * each line first continues the open containers, then may start new blocks, and the rest is added
  * as text to the innermost block (or as a lazy paragraph continuation).
  */
private[markdown] class BlockParser(input: String, gfm: Boolean, frontmatter: Boolean = false):
  import BlockParser.*
  import MarkdownText.*

  private val text  = LineReader.normalizeLineBreaks(input)
  private val lines =
    val ls = text.split("\n", -1)
    if text.endsWith("\n") then
      ls.dropRight(1)
    else
      ls

  private val lineOffsets =
    val offsets = Array.ofDim[Int](lines.length + 1)
    var i       = 0
    while i < lines.length do
      offsets(i + 1) = offsets(i) + lines(i).length + 1
      i += 1
    offsets

  /**
    * The YAML header and the first body line. A header counts only when it parses as a mapping (or
    * is empty); otherwise the `---` lines stay Markdown, so parsing never fails.
    */
  val (header: Option[JSONValue], firstLine: Int) =
    val found =
      if frontmatter then
        Frontmatter.split(text)
      else
        None
    found
      .flatMap { (closeStart, bodyStart) =>
        val yaml =
          try
            Some(YAML.parse(text.substring(0, closeStart)))
          catch
            case _: Exception =>
              None
        yaml.collect { case v @ (_: JSONObject | _: JSONNull) =>
          val line =
            if bodyStart >= text.length then
              lines.length
            else
              text.substring(0, bodyStart).count(_ == '\n')
          (Some(v), line)
        }
      }
      .getOrElse((None, 0))

  val refMap = mutable.Map.empty[String, (String, Option[String])]

  private val doc                    = BlockNode(BlockKind.Document, 0, 0)
  private var tip: BlockNode         = doc
  private var oldTip: BlockNode      = doc
  private var lastMatched: BlockNode = doc
  private var currentLine            = ""
  private var lineNumber             = firstLine - 1
  private var offset                 = 0
  private var column                 = 0
  private var nextNonspace           = 0
  private var nextNonspaceColumn     = 0
  private var indent                 = 0
  private var indented               = false
  private var blank                  = false
  private var partiallyConsumedTab   = false
  private var allClosed              = true
  private var lastLineLength         = 0

  def parse(): BlockNode =
    lines.iterator.drop(firstLine).foreach(incorporateLine)
    while tip ne doc do
      finalize(tip, lines.length - 1)
    finalize(doc, lines.length - 1)
    doc

  /**
    * The offset of the first body line (after the frontmatter) in the normalized text
    */
  def bodyStart: Int = lineOffsets(firstLine.min(lines.length))

  def textLength: Int = text.length

  def charAt(i: Int): Char = text.charAt(i)

  /**
    * Map offsets in the normalized text back to the input, whose line breaks may be `\r\n` or `\r`
    */
  val rawOffset: Int => Int =
    if input.indexOf('\r') < 0 then
      identity
    else
      val map = Array.ofDim[Int](text.length + 1)
      var i   = 0
      var j   = 0
      while i < text.length do
        map(i) = j
        if input.charAt(j) == '\r' && j + 1 < input.length && input.charAt(j + 1) == '\n' then
          j += 2
        else
          j += 1
        i += 1
      map(text.length) = input.length
      map(_)

  def spanOf(b: BlockNode): Span =
    val start = lineOffsets(b.startLine.min(lines.length)) + b.startCol
    val end   =
      if b.endLine < b.startLine then
        start
      else
        lineOffsets(b.endLine.min(lines.length)) + b.endCol
    Span.within(start, end.max(start).min(text.length))

  // ---- Line processing ----

  private def peek(pos: Int): Int =
    if pos < currentLine.length then
      currentLine.charAt(pos)
    else
      -1

  private def incorporateLine(rawLine: String): Unit =
    var container  = doc
    var allMatched = true
    oldTip = tip
    offset = 0
    column = 0
    blank = false
    partiallyConsumedTab = false
    lineNumber += 1
    currentLine =
      if rawLine.indexOf('\u0000') >= 0 then
        rawLine.replace('\u0000', '�')
      else
        rawLine

    // Try to continue each open container
    var lineConsumed = false
    var continue     = true
    while continue && !lineConsumed do
      container.lastChild match
        case child: BlockNode if child.open =>
          container = child
          findNextNonspace()
          continueBlock(container) match
            case 0 =>
            case 1 =>
              allMatched = false
              container = container.parent.nn
              continue = false
            case _ =>
              lineConsumed = true
        case _ =>
          continue = false

    if !lineConsumed then
      allClosed = container eq oldTip
      lastMatched = container

      var matchedLeaf =
        acceptsLines(container.kind) && container.kind != BlockKind.Paragraph &&
          container.kind != BlockKind.TableKind
      var searching = !matchedLeaf
      while searching do
        findNextNonspace()
        if !indented && !maybeSpecial(peek(nextNonspace)) &&
          !(gfm && container.kind == BlockKind.Paragraph)
        then
          advanceNextNonspace()
          searching = false
        else
          blockStart(container) match
            case 1 =>
              container = tip
            case 2 =>
              container = tip
              matchedLeaf = true
              searching = false
            case _ =>
              advanceNextNonspace()
              searching = false

      if !allClosed && !blank && tip.kind == BlockKind.Paragraph then
        // A lazy paragraph continuation
        addLine()
      else
        closeUnmatchedBlocks()
        if blank then
          container.lastChild match
            case c: BlockNode =>
              c.lastLineBlank = true
            case _ =>

        val k             = container.kind
        val lastLineBlank =
          blank && !(
            k == BlockKind.BlockQuote || k == BlockKind.Code && container.fenced ||
              k == BlockKind.Item && container.children.isEmpty && container.startLine == lineNumber
          )
        var c: BlockNode | Null = container
        while c != null do
          c.nn.lastLineBlank = lastLineBlank
          c = c.nn.parent

        if acceptsLines(k) then
          addLine()
          if k == BlockKind.Html && container.htmlBlockType >= 1 && container.htmlBlockType <= 5 &&
            htmlBlockEnds(container.htmlBlockType, currentLine.substring(offset))
          then
            lastLineLength = currentLine.length
            finalize(container, lineNumber)
        else if offset < currentLine.length && !blank then
          addChild(BlockKind.Paragraph, offset)
          advanceNextNonspace()
          addLine()
      end if
    end if
    lastLineLength = currentLine.length

  end incorporateLine

  private def maybeSpecial(c: Int): Boolean = c >= 0 && "#`~*+_=<>0123456789-|".indexOf(c) >= 0

  /**
    * 0: matched, 1: not matched, 2: the line was consumed (closing code fence)
    */
  private def continueBlock(b: BlockNode): Int =
    b.kind match
      case BlockKind.Document | BlockKind.ListKind =>
        0
      case BlockKind.BlockQuote =>
        if !indented && peek(nextNonspace) == '>' then
          advanceNextNonspace()
          advanceOffset(1, columns = false)
          if isSpaceOrTabCode(peek(offset)) then
            advanceOffset(1, columns = true)
          0
        else
          1
      case BlockKind.Item =>
        val data = b.listData.nn
        if blank then
          if b.children.isEmpty then
            1
          else
            advanceNextNonspace()
            0
        else if indent >= data.markerOffset + data.padding then
          advanceOffset(data.markerOffset + data.padding, columns = true)
          0
        else
          1
      case BlockKind.Heading | BlockKind.ThematicBreakKind =>
        1
      case BlockKind.Code =>
        if b.fenced then
          val closing =
            if indent <= 3 && peek(nextNonspace) == b.fenceChar then
              closingFenceLength(currentLine, nextNonspace, b.fenceChar)
            else
              0
          if closing >= b.fenceLength then
            lastLineLength = offset + indent + closing
            finalize(b, lineNumber)
            2
          else
            var i = b.fenceOffset
            while i > 0 && isSpaceOrTabCode(peek(offset)) do
              advanceOffset(1, columns = true)
              i -= 1
            0
        else if indent >= CodeIndent then
          advanceOffset(CodeIndent, columns = true)
          0
        else if blank then
          advanceNextNonspace()
          0
        else
          1
      case BlockKind.Html =>
        if blank && (b.htmlBlockType == 6 || b.htmlBlockType == 7) then
          1
        else
          0
      case BlockKind.Paragraph | BlockKind.TableKind =>
        if blank then
          1
        else
          0

  private def acceptsLines(k: BlockKind): Boolean =
    k == BlockKind.Paragraph || k == BlockKind.Code || k == BlockKind.Html ||
      k == BlockKind.TableKind

  private def canContain(parent: BlockKind, child: BlockKind): Boolean =
    parent match
      case BlockKind.Document | BlockKind.BlockQuote | BlockKind.Item =>
        child != BlockKind.Item
      case BlockKind.ListKind =>
        child == BlockKind.Item
      case _ =>
        false

  /**
    * Try block starts in order. 0: none, 1: a container started, 2: a leaf started
    */
  private val starts: Seq[BlockNode => Int] = Seq(
    startBlockQuote,
    startAtxHeading,
    startFencedCode,
    startHtmlBlock,
    startTable,
    startSetextHeading,
    startThematicBreak,
    startListItem,
    startIndentedCode
  )

  private def blockStart(container: BlockNode): Int =
    var result = 0
    val it     = starts.iterator
    while result == 0 && it.hasNext do
      result = it.next()(container)
    result

  private def startBlockQuote(container: BlockNode): Int =
    if !indented && peek(nextNonspace) == '>' && container.depth < MaxNesting then
      advanceNextNonspace()
      advanceOffset(1, columns = false)
      if isSpaceOrTabCode(peek(offset)) then
        advanceOffset(1, columns = true)
      closeUnmatchedBlocks()
      addChild(BlockKind.BlockQuote, nextNonspace)
      1
    else
      0

  private def startAtxHeading(container: BlockNode): Int =
    if indented || peek(nextNonspace) != '#' then
      0
    else
      var level = 0
      while peek(nextNonspace + level) == '#' do
        level += 1
      val after = peek(nextNonspace + level)
      if level > 6 || !(after == -1 || after == ' ' || after == '\t') then
        0
      else
        advanceNextNonspace()
        advanceOffset(level, columns = false)
        closeUnmatchedBlocks()
        val h = addChild(BlockKind.Heading, nextNonspace)
        h.level = level
        h.content.append(stripClosingHashes(currentLine.substring(offset)))
        advanceOffset(currentLine.length - offset, columns = false)
        2

  private def startFencedCode(container: BlockNode): Int =
    val c = peek(nextNonspace)
    if indented || (c != '`' && c != '~') then
      0
    else
      var n = 0
      while peek(nextNonspace + n) == c do
        n += 1
      if n < 3 || c == '`' && currentLine.indexOf('`', nextNonspace + n) >= 0 then
        0
      else
        closeUnmatchedBlocks()
        // The block starts before the fence's indentation, which also indents its content
        val b = addChild(BlockKind.Code, offset)
        b.fenced = true
        b.fenceLength = n
        b.fenceChar = c.toChar
        b.fenceOffset = indent
        advanceNextNonspace()
        advanceOffset(n, columns = false)
        2

  private def startHtmlBlock(container: BlockNode): Int =
    if indented || peek(nextNonspace) != '<' then
      0
    else
      val s = currentLine.substring(nextNonspace)
      var t = 1
      while t <= 7 && !(
          htmlBlockStarts(t, s) && (
            t < 7 ||
              container.kind != BlockKind.Paragraph &&
              !(!allClosed && !blank && tip.kind == BlockKind.Paragraph)
          )
        )
      do
        t += 1
      if t > 7 then
        0
      else
        closeUnmatchedBlocks()
        // Spaces are part of the HTML block, so the offset stays
        val b = addChild(BlockKind.Html, offset)
        b.htmlBlockType = t
        2

  private def startSetextHeading(container: BlockNode): Int =
    if indented || container.kind != BlockKind.Paragraph then
      0
    else
      val c = peek(nextNonspace)
      if (c != '=' && c != '-') || !isSetextUnderline(currentLine.substring(nextNonspace), c) then
        0
      else
        closeUnmatchedBlocks()
        // Link reference definitions at the start of the paragraph are not heading text
        val rest = consumeReferences(container.content.toString)
        if rest.isEmpty then
          0
        else
          val h = BlockNode(BlockKind.Heading, container.startLine, container.startCol)
          skipReferenceLines(h, container.content.toString, rest)
          h.level =
            if c == '=' then
              1
            else
              2
          h.content.append(rest)
          val parent = container.parent.nn
          parent.remove(container)
          parent.append(h)
          tip = h
          advanceOffset(currentLine.length - offset, columns = false)
          2

  private def startThematicBreak(container: BlockNode): Int =
    if !indented && isThematicBreak(currentLine.substring(nextNonspace)) then
      closeUnmatchedBlocks()
      addChild(BlockKind.ThematicBreakKind, nextNonspace)
      advanceOffset(currentLine.length - offset, columns = false)
      2
    else
      0

  private def startListItem(container: BlockNode): Int =
    if indented && container.kind != BlockKind.ListKind || container.depth >= MaxNesting then
      0
    else
      parseListMarker(container) match
        case None =>
          0
        case Some(data) =>
          closeUnmatchedBlocks()
          val sameList =
            tip.kind == BlockKind.ListKind && {
              val d = tip.listData.nn
              d.ordered == data.ordered && d.delimiter == data.delimiter &&
              d.bulletChar == data.bulletChar
            }
          if !sameList then
            val list = addChild(BlockKind.ListKind, nextNonspace)
            list.listData = data.copy()
          val item = addChild(BlockKind.Item, nextNonspace)
          item.listData = data
          1

  private def startIndentedCode(container: BlockNode): Int =
    if indented && tip.kind != BlockKind.Paragraph && !blank then
      // The block starts before the code indentation, so its source keeps it
      val start = offset
      advanceOffset(CodeIndent, columns = true)
      closeUnmatchedBlocks()
      addChild(BlockKind.Code, start)
      2
    else
      0

  /**
    * A GitHub Flavored Markdown table: the last line of a paragraph is the header row, and the
    * current line is the delimiter row with the same number of cells
    */
  private def startTable(container: BlockNode): Int =
    if !gfm || indented || container.kind != BlockKind.Paragraph || tip.ne(container) then
      0
    else
      val delimiterLine = currentLine.substring(nextNonspace)
      parseDelimiterRow(delimiterLine) match
        case None =>
          0
        case Some(alignments) =>
          // The paragraph content ends with "\n"; its last line is the header candidate
          val content     = container.content
          val headerStart = content.lastIndexOf("\n", content.length - 2) + 1
          val headerLine  = content.substring(headerStart, content.length - 1)
          if splitTableRow(headerLine).size != alignments.size then
            0
          else
            closeUnmatchedBlocks()
            val parent = container.parent.nn
            // The paragraph's last line is the previous source line
            val table = BlockNode(BlockKind.TableKind, lineNumber - 1, container.startCol)
            table.tableHeader = headerLine
            table.tableAlignments = alignments
            if headerStart == 0 then
              parent.remove(container)
            else
              // The lines before the header stay a paragraph
              content.setLength(headerStart)
              finalize(container, table.startLine - 1)
            parent.append(table)
            tip = table
            advanceOffset(currentLine.length - offset, columns = false)
            2

  // ---- List markers ----

  private def parseListMarker(container: BlockNode): Option[ListData] =
    if indent >= 4 then
      None
    else
      val c                     = peek(nextNonspace)
      var markerLength          = 0
      var data: ListData | Null = null
      if c == '*' || c == '+' || c == '-' then
        markerLength = 1
        data = ListData(false, c.toChar, 0, ' ', 0, indent)
      else
        var digits = 0
        while digits < 10 && Character.isDigit(peek(nextNonspace + digits)) do
          digits += 1
        val delim = peek(nextNonspace + digits)
        if digits >= 1 && digits <= 9 && (delim == '.' || delim == ')') then
          val start = currentLine.substring(nextNonspace, nextNonspace + digits).toInt
          if container.kind != BlockKind.Paragraph || start == 1 then
            markerLength = digits + 1
            data = ListData(true, ' ', start, delim.toChar, 0, indent)
      if data == null then
        None
      else
        val next = peek(nextNonspace + markerLength)
        if !(next == -1 || next == '\t' || next == ' ') then
          None
        else if container.kind == BlockKind.Paragraph &&
          currentLine.substring(nextNonspace + markerLength).forall(isSpaceOrTab)
        then
          // An empty item cannot interrupt a paragraph
          None
        else
          advanceNextNonspace()
          advanceOffset(markerLength, columns = true)
          val spacesStartCol    = column
          val spacesStartOffset = offset
          var continue          = true
          while continue do
            advanceOffset(1, columns = true)
            continue = column - spacesStartCol < 5 && isSpaceOrTabCode(peek(offset))
          val blankItem         = peek(offset) == -1
          val spacesAfterMarker = column - spacesStartCol
          val d                 = data.nn
          if spacesAfterMarker >= 5 || spacesAfterMarker < 1 || blankItem then
            d.padding = markerLength + 1
            column = spacesStartCol
            offset = spacesStartOffset
            if isSpaceOrTabCode(peek(offset)) then
              advanceOffset(1, columns = true)
          else
            d.padding = markerLength + spacesAfterMarker
          Some(d)
      end if

  // ---- Cursor ----

  private def isSpaceOrTabCode(c: Int): Boolean = c == ' ' || c == '\t'

  private def findNextNonspace(): Unit =
    var i    = offset
    var cols = column
    var c    = peek(i)
    while c == ' ' || c == '\t' do
      if c == ' ' then
        cols += 1
      else
        cols += 4 - (cols % 4)
      i += 1
      c = peek(i)
    blank = c == -1
    nextNonspace = i
    nextNonspaceColumn = cols
    indent = nextNonspaceColumn - column
    indented = indent >= CodeIndent

  private def advanceNextNonspace(): Unit =
    offset = nextNonspace
    column = nextNonspaceColumn
    partiallyConsumedTab = false

  private def advanceOffset(count: Int, columns: Boolean): Unit =
    var n = count
    while n > 0 && offset < currentLine.length do
      if currentLine.charAt(offset) == '\t' then
        val charsToTab = 4 - (column % 4)
        if columns then
          partiallyConsumedTab = charsToTab > n
          val charsToAdvance = charsToTab.min(n)
          column += charsToAdvance
          if !partiallyConsumedTab then
            offset += 1
          n -= charsToAdvance
        else
          partiallyConsumedTab = false
          column += charsToTab
          offset += 1
          n -= 1
      else
        partiallyConsumedTab = false
        offset += 1
        column += 1
        n -= 1

  private def addLine(): Unit =
    if partiallyConsumedTab then
      offset += 1
      tip.content.append(" " * (4 - (column % 4)))
    tip.content.append(currentLine.substring(offset.min(currentLine.length))).append('\n')

  private def addChild(kind: BlockKind, startOffset: Int): BlockNode =
    while !canContain(tip.kind, kind) do
      finalize(tip, lineNumber - 1)
    val b = BlockNode(kind, lineNumber, startOffset)
    tip.append(b)
    tip = b
    b

  private def closeUnmatchedBlocks(): Unit =
    if !allClosed then
      while oldTip ne lastMatched do
        val parent = oldTip.parent.nn
        finalize(oldTip, lineNumber - 1)
        oldTip = parent
      allClosed = true

  // ---- Finalization ----

  private def finalize(b: BlockNode, line: Int): Unit =
    b.open = false
    b.endLine = line
    b.endCol =
      if line == lineNumber then
        lastLineLength
      else if line >= 0 && line < lines.length then
        lines(line).length
      else
        0
    b.kind match
      case BlockKind.Paragraph =>
        val content = b.content.toString
        val rest    = consumeReferences(content)
        b.content.clear()
        b.content.append(rest)
        skipReferenceLines(b, content, rest)
        if rest.forall(c => c == ' ' || c == '\t' || c == '\n') then
          b.parent.nn.remove(b)
      case BlockKind.Code =>
        val content = b.content.toString
        if b.fenced then
          val nl = content.indexOf('\n')
          b.info = unescapeString(content.substring(0, nl.max(0)).trim)
          b.literal = content.substring(nl + 1)
        else
          val ls = content.split("\n", -1).toBuffer
          while ls.nonEmpty && ls.last.forall(isSpaceOrTab) do
            ls.remove(ls.length - 1)
          b.literal = ls.mkString("", "\n", "\n")
      case BlockKind.Html =>
        b.literal = b.content.toString.stripSuffix("\n")
      case BlockKind.TableKind =>
        // The delimiter row adds an empty line; a blank line would have closed the table
        b.content.toString.split("\n").filter(_.trim.nonEmpty).foreach(row => b.tableRows += row)
      case BlockKind.ListKind =>
        val data  = b.listData.nn
        val items = b.children
        var i     = 0
        while data.tight && i < items.length do
          val item = items(i)
          if endsWithBlankLine(item) && i < items.length - 1 then
            data.tight = false
          var j = 0
          while data.tight && j < item.children.length do
            if endsWithBlankLine(item.children(j)) &&
              (i < items.length - 1 || j < item.children.length - 1)
            then
              data.tight = false
            j += 1
          i += 1
      case _ =>
    end match
    if b ne doc then
      tip = b.parent.nn

  end finalize

  private def endsWithBlankLine(block: BlockNode): Boolean =
    var b: BlockNode | Null = block
    var result              = false
    var continue            = true
    while continue && b != null do
      val cur = b.nn
      if cur.lastLineBlank then
        result = true
        continue = false
      else if !cur.lastLineChecked && (cur.kind == BlockKind.ListKind || cur.kind == BlockKind.Item)
      then
        cur.lastLineChecked = true
        b = cur.lastChild
      else
        cur.lastLineChecked = true
        continue = false
    result

  /**
    * Parse link reference definitions at the start of the text, and return the remaining text
    */
  /**
    * Reference definitions are not part of a paragraph or heading; their lines become the trivia
    * before it, so that the block's source covers only its own text
    */
  private def skipReferenceLines(b: BlockNode, content: String, rest: String): Unit =
    val skipped = content.length - rest.length
    if skipped > 0 && rest.nonEmpty then
      val newLines = content.substring(0, skipped).count(_ == '\n')
      if newLines > 0 then
        b.startLine += newLines
        val firstLine = rest.takeWhile(_ != '\n')
        b.startCol = lines(b.startLine).lastIndexOf(firstLine).max(0)

  private def consumeReferences(s: String): String =
    val parser   = InlineParser(s, refMap, gfm)
    var start    = 0
    var continue = true
    while continue && start < s.length && s.charAt(start) == '[' do
      val consumed = parser.parseReference(start)
      if consumed == 0 then
        continue = false
      else
        start += consumed
    s.substring(start)

end BlockParser

private[markdown] object BlockParser:
  val CodeIndent = 4

  /**
    * The maximum depth of nested block quotes and lists. Deeper markers are read as text, so that
    * converting and rendering the tree cannot overflow the stack (Scala.js has a small one).
    */
  val MaxNesting = 100

  def isThematicBreak(s: String): Boolean =
    val c =
      if s.isEmpty then
        ' '
      else
        s.charAt(0)
    (c == '*' || c == '-' || c == '_') && s.count(_ == c) >= 3 &&
    s.forall(x => x == c || x == ' ' || x == '\t')

  def isSetextUnderline(s: String, c: Int): Boolean =
    var i = 0
    while i < s.length && s.charAt(i) == c do
      i += 1
    i > 0 && s.substring(i).forall(MarkdownText.isSpaceOrTab)

  /**
    * The length of a closing code fence at `start`, or 0 when the line is not a closing fence
    */
  def closingFenceLength(line: String, start: Int, fenceChar: Char): Int =
    var i = start
    while i < line.length && line.charAt(i) == fenceChar do
      i += 1
    val n = i - start
    if n >= 3 && line.substring(i).forall(MarkdownText.isSpaceOrTab) then
      n
    else
      0

  /**
    * Remove the optional closing `#` sequence of an ATX heading
    */
  def stripClosingHashes(s: String): String =
    val trimmed = s.reverse.dropWhile(MarkdownText.isSpaceOrTab).reverse
    if trimmed.forall(_ == '#') then
      ""
    else
      var i = trimmed.length
      while i > 0 && trimmed.charAt(i - 1) == '#' do
        i -= 1
      if i < trimmed.length && MarkdownText.isSpaceOrTab(trimmed.charAt(i - 1)) then
        trimmed.substring(0, i).reverse.dropWhile(MarkdownText.isSpaceOrTab).reverse
      else
        trimmed

  private val blockTagNames: Set[String] = Set(
    "address",
    "article",
    "aside",
    "base",
    "basefont",
    "blockquote",
    "body",
    "caption",
    "center",
    "col",
    "colgroup",
    "dd",
    "details",
    "dialog",
    "dir",
    "div",
    "dl",
    "dt",
    "fieldset",
    "figcaption",
    "figure",
    "footer",
    "form",
    "frame",
    "frameset",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "head",
    "header",
    "hr",
    "html",
    "iframe",
    "legend",
    "li",
    "link",
    "main",
    "menu",
    "menuitem",
    "nav",
    "noframes",
    "ol",
    "optgroup",
    "option",
    "p",
    "param",
    "search",
    "section",
    "summary",
    "table",
    "tbody",
    "td",
    "tfoot",
    "th",
    "thead",
    "title",
    "tr",
    "track",
    "ul"
  )

  private val rawTagNames = Seq("pre", "script", "style", "textarea")

  def htmlBlockStarts(blockType: Int, s: String): Boolean =
    val lower = MarkdownText.asciiLower(s)
    blockType match
      case 1 =>
        rawTagNames.exists { t =>
          lower.startsWith(s"<${t}") && {
            val i = t.length + 1
            i == s.length || s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '>'
          }
        }
      case 2 =>
        s.startsWith("<!--")
      case 3 =>
        s.startsWith("<?")
      case 4 =>
        s.length > 2 && s.startsWith("<!") && s.charAt(2).isLetter && s.charAt(2) < 128
      case 5 =>
        s.startsWith("<![CDATA[")
      case 6 =>
        val nameStart =
          if s.startsWith("</") then
            2
          else
            1
        var i = nameStart
        while i < s.length && s.charAt(i).isLetterOrDigit && s.charAt(i) < 128 do
          i += 1
        val name = lower.substring(nameStart, i)
        blockTagNames.contains(name) && (
          i == s.length || s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '>' ||
            s.startsWith("/>", i)
        )
      case _ =>
        HtmlSyntax.matchTag(s, 0) match
          case Some(end) =>
            val name = HtmlSyntax.tagName(s)
            !rawTagNames.contains(name) && s.substring(end).forall(MarkdownText.isSpaceOrTab) &&
            (s.startsWith("</") || s.charAt(1).isLetter) && !s.startsWith("<!") &&
            !s.startsWith("<?")
          case None =>
            false
    end match

  end htmlBlockStarts

  def htmlBlockEnds(blockType: Int, s: String): Boolean =
    blockType match
      case 1 =>
        val lower = MarkdownText.asciiLower(s)
        rawTagNames.exists(t => lower.contains(s"</${t}>"))
      case 2 =>
        s.contains("-->")
      case 3 =>
        s.contains("?>")
      case 4 =>
        s.contains(">")
      case 5 =>
        s.contains("]]>")
      case _ =>
        false

  /**
    * Parse a GFM table delimiter row such as `| :--- | ---: |`
    */
  def parseDelimiterRow(line: String): Option[Seq[TableAlignment]] =
    if !line.contains('|') && !line.contains(':') then
      None
    else
      val cells  = splitTableRow(line)
      val aligns = cells.map { c =>
        val t    = c.trim
        val core = t.stripPrefix(":").stripSuffix(":")
        if core.isEmpty || !core.forall(_ == '-') then
          None
        else
          Some(
            (t.startsWith(":"), t.endsWith(":")) match
              case (true, true) =>
                TableAlignment.Center
              case (true, false) =>
                TableAlignment.Left
              case (false, true) =>
                TableAlignment.Right
              case _ =>
                TableAlignment.Default
          )
      }
      if cells.isEmpty || aligns.exists(_.isEmpty) then
        None
      else
        Some(aligns.flatten)

  /**
    * Split a table row into raw cell texts at unescaped pipes, dropping the optional outer pipes
    */
  def splitTableRow(line: String): Seq[String] =
    val s     = line.trim
    val cells = mutable.ArrayBuffer.empty[String]
    val cell  = StringBuilder()
    var i     =
      if s.startsWith("|") then
        1
      else
        0
    var trailingPipe = false
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length && s.charAt(i + 1) == '|' then
        cell.append('|')
        i += 2
      else if c == '\\' && i + 1 < s.length then
        cell.append(c).append(s.charAt(i + 1))
        i += 2
      else if c == '|' then
        cells += cell.result().trim
        cell.clear()
        i += 1
        trailingPipe = i == s.length
      else
        cell.append(c)
        i += 1
    if !trailingPipe then
      cells += cell.result().trim
    cells.toSeq

end BlockParser
