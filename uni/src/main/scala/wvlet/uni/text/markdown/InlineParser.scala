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

import scala.collection.mutable

private[markdown] enum InlineKind:
  case Root,
    Text,
    Code,
    Emph,
    Strong,
    Del,
    Link,
    Image,
    Html,
    SoftBreak,
    HardBreak

/**
  * A mutable inline node. Emphasis and links are built by moving runs of siblings under a new node,
  * so siblings form a doubly-linked list.
  */
private[markdown] final class InlineNode(var kind: InlineKind, var literal: String = ""):
  var parent: InlineNode | Null = null
  var first: InlineNode | Null  = null
  var last: InlineNode | Null   = null
  var prev: InlineNode | Null   = null
  var next: InlineNode | Null   = null
  var destination               = ""
  var title: Option[String]     = None

  def appendChild(c: InlineNode): Unit =
    c.unlink()
    c.parent = this
    if last == null then
      first = c
      last = c
    else
      last.nn.next = c
      c.prev = last
      last = c

  def insertAfter(sibling: InlineNode): Unit =
    sibling.unlink()
    sibling.next = next
    if next != null then
      next.nn.prev = sibling
    sibling.prev = this
    next = sibling
    sibling.parent = parent
    if sibling.next == null && parent != null then
      parent.nn.last = sibling

  def unlink(): Unit =
    if prev != null then
      prev.nn.next = next
    else if parent != null then
      parent.nn.first = next
    if next != null then
      next.nn.prev = prev
    else if parent != null then
      parent.nn.last = prev
    parent = null
    next = null
    prev = null

end InlineNode

/**
  * Parses inline content (paragraph and heading text) with the CommonMark delimiter algorithm, and
  * also parses link reference definitions.
  */
private[markdown] class InlineParser(
    subject: String,
    refMap: mutable.Map[String, (String, Option[String])],
    gfm: Boolean
):
  import InlineParser.*
  import MarkdownText.*

  private var pos                      = 0
  private var delimiters: Delim | Null = null
  private var brackets: Bracket | Null = null

  private def peek: Int =
    if pos < subject.length then
      subject.charAt(pos)
    else
      -1

  private def text(s: String): InlineNode = InlineNode(InlineKind.Text, s)

  def parse(): Seq[Inline] =
    val root = InlineNode(InlineKind.Root)
    while parseInline(root) do
      (
    )
    processEmphasis(null)
    val inlines = toInlines(root)
    if gfm then
      Autolinks.linkify(inlines)
    else
      inlines

  private def parseInline(block: InlineNode): Boolean =
    val c = peek
    if c == -1 then
      false
    else
      val handled =
        c match
          case '\n' =>
            parseNewline(block)
          case '\\' =>
            parseBackslash(block)
          case '`' =>
            parseBackticks(block)
          case '*' | '_' =>
            handleDelim(c.toChar, block)
          case '~' if gfm =>
            handleDelim('~', block)
          case '[' =>
            parseOpenBracket(block)
          case '!' =>
            parseBang(block)
          case ']' =>
            parseCloseBracket(block)
          case '<' =>
            parseAutolink(block) || parseHtmlTag(block)
          case '&' =>
            parseEntity(block)
          case _ =>
            parseString(block)
      if !handled then
        pos += 1
        block.appendChild(text(c.toChar.toString))
      true
    end if

  end parseInline

  private def isSpecial(c: Char): Boolean =
    c == '\n' || c == '`' || c == '[' || c == ']' || c == '\\' || c == '!' || c == '<' ||
      c == '&' || c == '*' || c == '_' ||
      (gfm && c == '~')

  private def parseString(block: InlineNode): Boolean =
    val start = pos
    while pos < subject.length && !isSpecial(subject.charAt(pos)) do
      pos += 1
    if pos > start then
      block.appendChild(text(subject.substring(start, pos)))
      true
    else
      false

  private def parseNewline(block: InlineNode): Boolean =
    pos += 1
    block.last match
      case t: InlineNode if t.kind == InlineKind.Text && t.literal.endsWith(" ") =>
        val hard = t.literal.endsWith("  ")
        t.literal = t.literal.reverse.dropWhile(_ == ' ').reverse
        block.appendChild(
          InlineNode(
            if hard then
              InlineKind.HardBreak
            else
              InlineKind.SoftBreak
          )
        )
      case _ =>
        block.appendChild(InlineNode(InlineKind.SoftBreak))
    while peek == ' ' do
      pos += 1
    true

  private def parseBackslash(block: InlineNode): Boolean =
    pos += 1
    if peek == '\n' then
      pos += 1
      block.appendChild(InlineNode(InlineKind.HardBreak))
    else if peek != -1 && isAsciiPunctuation(peek.toChar) then
      block.appendChild(text(peek.toChar.toString))
      pos += 1
    else
      block.appendChild(text("\\"))
    true

  private def parseBackticks(block: InlineNode): Boolean =
    val start = pos
    while peek == '`' do
      pos += 1
    val ticks          = pos - start
    val afterOpenTicks = pos
    var found          = -1
    var i              = pos
    while found < 0 && i < subject.length do
      if subject.charAt(i) == '`' then
        val runStart = i
        while i < subject.length && subject.charAt(i) == '`' do
          i += 1
        if i - runStart == ticks then
          found = runStart
      else
        i += 1
    if found < 0 then
      pos = afterOpenTicks
      block.appendChild(text("`" * ticks))
    else
      val contents = subject.substring(afterOpenTicks, found).replace('\n', ' ')
      // Strip one leading and trailing space when both exist and the content is not all spaces
      val code =
        if contents.length >= 2 && contents.head == ' ' && contents.last == ' ' &&
          contents.exists(_ != ' ')
        then
          contents.substring(1, contents.length - 1)
        else
          contents
      block.appendChild(InlineNode(InlineKind.Code, code))
      pos = found + ticks
    true

  end parseBackticks

  // ---- Emphasis ----

  private def handleDelim(cc: Char, block: InlineNode): Boolean =
    val start = pos
    while peek == cc do
      pos += 1
    val numDelims = pos - start
    val before    =
      if start == 0 then
        '\n'.toInt
      else
        subject.codePointBefore(start)
    val after =
      if pos >= subject.length then
        '\n'.toInt
      else
        subject.codePointAt(pos)
    val afterIsWhitespace   = isUnicodeWhitespace(after)
    val afterIsPunctuation  = isUnicodePunctuation(after)
    val beforeIsWhitespace  = isUnicodeWhitespace(before)
    val beforeIsPunctuation = isUnicodePunctuation(before)
    val leftFlanking        =
      !afterIsWhitespace && (!afterIsPunctuation || beforeIsWhitespace || beforeIsPunctuation)
    val rightFlanking =
      !beforeIsWhitespace && (!beforeIsPunctuation || afterIsWhitespace || afterIsPunctuation)
    val (canOpen, canClose) =
      if cc == '_' then
        (
          leftFlanking && (!rightFlanking || beforeIsPunctuation),
          rightFlanking && (!leftFlanking || afterIsPunctuation)
        )
      else
        (leftFlanking, rightFlanking)
    val node = text(subject.substring(start, pos))
    block.appendChild(node)
    if (canOpen || canClose) && (cc != '~' || numDelims <= 2) then
      val d = Delim(cc, numDelims, numDelims, node, delimiters, null, canOpen, canClose)
      if delimiters != null then
        delimiters.nn.next = d
      delimiters = d
    true

  end handleDelim

  private def removeDelimiter(d: Delim): Unit =
    if d.previous != null then
      d.previous.nn.next = d.next
    if d.next != null then
      d.next.nn.previous = d.previous
    else
      delimiters = d.previous

  private def processEmphasis(stackBottom: Delim | Null): Unit =
    val openersBottom = mutable.Map.empty[(Char, Boolean, Int), Delim | Null]
    var closer        = delimiters
    while closer != null && (closer.nn.previous ne stackBottom) do
      closer = closer.nn.previous
    while closer != null do
      val c = closer.nn
      if !c.canClose then
        closer = c.next
      else
        val bottomKey = (
          c.cc,
          c.canOpen,
          if c.cc == '~' then
            c.origDelims
          else
            c.origDelims % 3
        )
        val bottom      = openersBottom.getOrElse(bottomKey, stackBottom)
        var opener      = c.previous
        var openerFound = false
        while !openerFound && opener != null && (opener ne stackBottom) && (opener ne bottom) do
          val o        = opener.nn
          val oddMatch =
            c.cc != '~' &&
              (c.canOpen || o.canClose) && c.origDelims % 3 != 0 &&
              (o.origDelims + c.origDelims)             % 3 == 0
          val sameLength = c.cc != '~' || o.numDelims == c.numDelims
          if o.cc == c.cc && o.canOpen && !oddMatch && sameLength then
            openerFound = true
          else
            opener = o.previous
        val oldCloser = c
        if !openerFound then
          closer = c.next
          openersBottom(bottomKey) = oldCloser.previous
          if !oldCloser.canOpen then
            removeDelimiter(oldCloser)
        else
          val o         = opener.nn
          val useDelims =
            if c.cc == '~' then
              c.numDelims
            else if c.numDelims >= 2 && o.numDelims >= 2 then
              2
            else
              1
          val openerNode = o.node
          val closerNode = c.node
          o.numDelims -= useDelims
          c.numDelims -= useDelims
          openerNode.literal = openerNode
            .literal
            .substring(0, openerNode.literal.length - useDelims)
          closerNode.literal = closerNode
            .literal
            .substring(0, closerNode.literal.length - useDelims)
          val kind =
            if c.cc == '~' then
              InlineKind.Del
            else if useDelims == 1 then
              InlineKind.Emph
            else
              InlineKind.Strong
          val emph = InlineNode(kind)
          var tmp  = openerNode.next
          while tmp != null && (tmp ne closerNode) do
            val n = tmp.nn.next
            emph.appendChild(tmp.nn)
            tmp = n
          openerNode.insertAfter(emph)
          // Remove the delimiters between the opener and the closer
          if o.next ne c then
            o.next = c
            c.previous = o
          if o.numDelims == 0 then
            openerNode.unlink()
            removeDelimiter(o)
          if c.numDelims == 0 then
            closerNode.unlink()
            val nextCloser = c.next
            removeDelimiter(c)
            closer = nextCloser
        end if
      end if
    end while
    while delimiters != null && (delimiters ne stackBottom) do
      removeDelimiter(delimiters.nn)

  end processEmphasis

  // ---- Links and images ----

  private def addBracket(node: InlineNode, index: Int, image: Boolean): Unit =
    if brackets != null then
      brackets.nn.bracketAfter = true
    brackets = Bracket(node, brackets, delimiters, index, image)

  private def parseOpenBracket(block: InlineNode): Boolean =
    val start = pos
    pos += 1
    val node = text("[")
    block.appendChild(node)
    addBracket(node, start, image = false)
    true

  private def parseBang(block: InlineNode): Boolean =
    val start = pos
    pos += 1
    if peek == '[' then
      pos += 1
      val node = text("![")
      block.appendChild(node)
      addBracket(node, start + 1, image = true)
    else
      block.appendChild(text("!"))
    true

  private def parseCloseBracket(block: InlineNode): Boolean =
    pos += 1
    val startPos = pos
    brackets match
      case null =>
        block.appendChild(text("]"))
      case opener: Bracket if !opener.active =>
        block.appendChild(text("]"))
        brackets = opener.previous
      case opener: Bracket =>
        var matched               = false
        var dest                  = ""
        var title: Option[String] = None
        val savePos               = pos
        if peek == '(' then
          pos += 1
          spnl()
          parseLinkDestination() match
            case Some(d) =>
              dest = d
              val beforeTitle = pos
              spnl()
              if pos > beforeTitle then
                title = parseLinkTitle()
              spnl()
              if peek == ')' then
                pos += 1
                matched = true
              else
                pos = savePos
            case None =>
              pos = savePos
        if !matched then
          val beforeLabel = pos
          val n           = parseLinkLabel()
          val refLabel    =
            if n > 2 then
              Some(subject.substring(beforeLabel + 1, beforeLabel + n - 1))
            else if !opener.bracketAfter then
              Some(subject.substring(opener.index + 1, startPos - 1))
            else
              None
          if n == 0 then
            pos = savePos
          refLabel.flatMap(l => refMap.get(normalizeLabel(l))) match
            case Some((d, t)) =>
              dest = d
              title = t
              matched = true
            case None =>
        if matched then
          val node = InlineNode(
            if opener.image then
              InlineKind.Image
            else
              InlineKind.Link
          )
          node.destination = dest
          node.title = title
          var tmp = opener.node.next
          while tmp != null do
            val n = tmp.nn.next
            node.appendChild(tmp.nn)
            tmp = n
          block.appendChild(node)
          processEmphasis(opener.previousDelimiter)
          brackets = opener.previous
          opener.node.unlink()
          // No links in links: deactivate earlier link openers
          if !opener.image then
            var b = brackets
            while b != null do
              if !b.nn.image then
                b.nn.active = false
              b = b.nn.previous
        else
          brackets = opener.previous
          pos = startPos
          block.appendChild(text("]"))
    end match
    true

  end parseCloseBracket

  /**
    * Skip spaces and tabs, including up to one line break
    */
  private def spnl(): Unit =
    while peek == ' ' || peek == '\t' do
      pos += 1
    if peek == '\n' then
      pos += 1
      while peek == ' ' || peek == '\t' do
        pos += 1

  private def parseLinkDestination(): Option[String] =
    if peek == '<' then
      var i      = pos + 1
      var result = Option.empty[String]
      var done   = false
      while !done && i < subject.length do
        subject.charAt(i) match
          case '>' =>
            result = Some(normalizeUri(unescapeString(subject.substring(pos + 1, i))))
            pos = i + 1
            done = true
          case '\\' if i + 1 < subject.length && isAsciiPunctuation(subject.charAt(i + 1)) =>
            i += 2
          case '<' | '\n' =>
            done = true
          case _ =>
            i += 1
      result
    else
      val start      = pos
      var openParens = 0
      var done       = false
      while !done && pos < subject.length do
        val c = subject.charAt(pos)
        if c == '\\' && pos + 1 < subject.length && isAsciiPunctuation(subject.charAt(pos + 1)) then
          pos += 2
        else if c == '(' then
          pos += 1
          openParens += 1
        else if c == ')' then
          if openParens < 1 then
            done = true
          else
            pos += 1
            openParens -= 1
        else if c <= ' ' then
          done = true
        else
          pos += 1
      if pos == start && peek != ')' || openParens != 0 then
        pos = start
        None
      else
        Some(normalizeUri(unescapeString(subject.substring(start, pos))))

  private def parseLinkTitle(): Option[String] =
    val open  = peek
    val close =
      open match
        case '"' =>
          '"'
        case '\'' =>
          '\''
        case '(' =>
          ')'
        case _ =>
          -1
    if close == -1 then
      None
    else
      var i      = pos + 1
      var result = Option.empty[String]
      var done   = false
      while !done && i < subject.length do
        val c = subject.charAt(i)
        if c == '\\' && i + 1 < subject.length then
          i += 2
        else if c == close then
          result = Some(unescapeString(subject.substring(pos + 1, i)))
          pos = i + 1
          done = true
        else if open == '(' && c == '(' then
          done = true
        else
          i += 1
      result

  end parseLinkTitle

  /**
    * The length of a link label `[...]` at the current position, or 0
    */
  private def parseLinkLabel(): Int =
    if peek != '[' then
      0
    else
      var i      = pos + 1
      var result = 0
      var done   = false
      while !done && i < subject.length && i - pos <= 1000 do
        subject.charAt(i) match
          case '\\' if i + 1 < subject.length =>
            i += 2
          case '[' =>
            done = true
          case ']' =>
            result = i + 1 - pos
            done = true
          case _ =>
            i += 1
      if result > 0 then
        pos += result
      result

  /**
    * Parse a link reference definition at the start of the subject. Returns the number of consumed
    * characters, or 0.
    */
  def parseReference(): Int =
    pos = 0
    val labelLength = parseLinkLabel()
    if labelLength == 0 || peek != ':' then
      0
    else
      val label = subject.substring(1, labelLength - 1)
      pos += 1
      spnl()
      parseLinkDestination() match
        case None =>
          0
        case Some(dest) =>
          val beforeTitle = pos
          spnl()
          var title =
            if pos != beforeTitle then
              parseLinkTitle()
            else
              None
          if title.isEmpty then
            pos = beforeTitle
          var atLineEnd = skipSpacesToLineEnd()
          if !atLineEnd && title.isDefined then
            // The title is not at the line end, but the definition can still end after the URL
            title = None
            pos = beforeTitle
            atLineEnd = skipSpacesToLineEnd()
          val normalized = normalizeLabel(label)
          if !atLineEnd || normalized.isEmpty then
            0
          else
            if !refMap.contains(normalized) then
              refMap(normalized) = (dest, title)
            pos
    end if

  end parseReference

  private def skipSpacesToLineEnd(): Boolean =
    var i = pos
    while i < subject.length && (subject.charAt(i) == ' ' || subject.charAt(i) == '\t') do
      i += 1
    if i == subject.length then
      pos = i
      true
    else if subject.charAt(i) == '\n' then
      pos = i + 1
      true
    else
      false

  // ---- Autolinks, raw HTML, entities ----

  private def parseAutolink(block: InlineNode): Boolean =
    val end = subject.indexOf('>', pos + 1)
    if end < 0 then
      false
    else
      val body = subject.substring(pos + 1, end)
      val dest =
        if isUriAutolink(body) then
          Some(normalizeUri(body))
        else if isEmailAutolink(body) then
          Some(normalizeUri(s"mailto:${body}"))
        else
          None
      dest match
        case Some(d) =>
          val link = InlineNode(InlineKind.Link)
          link.destination = d
          link.appendChild(text(body))
          block.appendChild(link)
          pos = end + 1
          true
        case None =>
          false

  private def parseHtmlTag(block: InlineNode): Boolean =
    HtmlSyntax.matchTag(subject, pos) match
      case Some(end) =>
        block.appendChild(InlineNode(InlineKind.Html, subject.substring(pos, end)))
        pos = end
        true
      case None =>
        false

  private def parseEntity(block: InlineNode): Boolean =
    decodeEntity(subject, pos) match
      case Some((decoded, next)) =>
        block.appendChild(text(decoded))
        pos = next
        true
      case None =>
        false

  // ---- Conversion ----

  private def toInlines(parent: InlineNode): Seq[Inline] =
    val out = mutable.ArrayBuffer.empty[Inline]
    var n   = parent.first
    while n != null do
      val node                   = n.nn
      val inline: Option[Inline] =
        node.kind match
          case InlineKind.Text =>
            if node.literal.isEmpty then
              None
            else
              out.lastOption match
                case Some(Inline.Text(prev)) =>
                  out(out.length - 1) = Inline.Text(prev + node.literal)
                  None
                case _ =>
                  Some(Inline.Text(node.literal))
          case InlineKind.Code =>
            Some(Inline.Code(node.literal))
          case InlineKind.Emph =>
            Some(Inline.Emphasis(toInlines(node)))
          case InlineKind.Strong =>
            Some(Inline.Strong(toInlines(node)))
          case InlineKind.Del =>
            Some(Inline.Strikethrough(toInlines(node)))
          case InlineKind.Link =>
            Some(Inline.Link(node.destination, node.title, toInlines(node)))
          case InlineKind.Image =>
            Some(Inline.Image(node.destination, node.title, toInlines(node)))
          case InlineKind.Html =>
            Some(Inline.HtmlInline(node.literal))
          case InlineKind.SoftBreak =>
            Some(Inline.SoftBreak)
          case InlineKind.HardBreak =>
            Some(Inline.HardBreak)
          case InlineKind.Root =>
            None
      inline.foreach(out += _)
      n = node.next
    end while
    out.toSeq

  end toInlines

end InlineParser

private[markdown] object InlineParser:

  final class Delim(
      val cc: Char,
      var numDelims: Int,
      val origDelims: Int,
      val node: InlineNode,
      var previous: Delim | Null,
      var next: Delim | Null,
      val canOpen: Boolean,
      val canClose: Boolean
  )

  final class Bracket(
      val node: InlineNode,
      val previous: Bracket | Null,
      val previousDelimiter: Delim | Null,
      val index: Int,
      val image: Boolean
  ):
    var active       = true
    var bracketAfter = false

  /**
    * `scheme:...` with a 2-32 character scheme and no spaces, `<`, `>`, or control characters
    */
  def isUriAutolink(s: String): Boolean =
    val colon = s.indexOf(':')
    colon >= 2 && colon <= 32 && s.charAt(0).isLetter && s.charAt(0) < 128 &&
    s.substring(1, colon)
      .forall(c => c < 128 && (c.isLetterOrDigit || c == '+' || c == '.' || c == '-')) &&
    s.substring(colon + 1).forall(c => c > ' ' && c != '<' && c != '>')

  private val emailLocalChars = ".!#$%&'*+/=?^_`{|}~-"

  def isEmailAutolink(s: String): Boolean =
    val at = s.indexOf('@')
    at > 0 &&
    s.substring(0, at)
      .forall(c => c < 128 && (c.isLetterOrDigit || emailLocalChars.indexOf(c) >= 0)) && {
      val labels = s.substring(at + 1).split("\\.", -1)
      labels.nonEmpty &&
      labels.forall { l =>
        l.nonEmpty && l.length <= 63 && l.forall(c => c < 128 && (c.isLetterOrDigit || c == '-')) &&
        l.head != '-' && l.last != '-'
      }
    }

end InlineParser

/**
  * Raw HTML syntax shared by inline HTML and HTML blocks (type 7)
  */
private[markdown] object HtmlSyntax:

  private def isTagNameChar(c: Char): Boolean = c < 128 && (c.isLetterOrDigit || c == '-')

  private def isWhite(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n'

  /**
    * The lower-case tag name of `<name` or `</name`
    */
  def tagName(s: String): String =
    val start =
      if s.startsWith("</") then
        2
      else
        1
    var i = start
    while i < s.length && isTagNameChar(s.charAt(i)) do
      i += 1
    s.substring(start, i).toLowerCase

  /**
    * Match an HTML open tag, closing tag, comment, processing instruction, declaration, or CDATA
    * section at `start`, and return the index after it
    */
  def matchTag(s: String, start: Int): Option[Int] =
    if start + 1 >= s.length || s.charAt(start) != '<' then
      None
    else
      val c = s.charAt(start + 1)
      if c.isLetter && c < 128 then
        openTag(s, start + 1)
      else if c == '/' then
        closingTag(s, start + 2)
      else if s.startsWith("<!--", start) then
        if s.startsWith("<!-->", start) then
          Some(start + 5)
        else if s.startsWith("<!--->", start) then
          Some(start + 6)
        else
          val end = s.indexOf("-->", start + 4)
          Option.when(end >= 0)(end + 3)
      else if c == '?' then
        val end = s.indexOf("?>", start + 2)
        Option.when(end >= 0)(end + 2)
      else if s.startsWith("<![CDATA[", start) then
        val end = s.indexOf("]]>", start + 9)
        Option.when(end >= 0)(end + 3)
      else if c == '!' && start + 2 < s.length && s.charAt(start + 2).isLetter &&
        s.charAt(start + 2) < 128
      then
        val end = s.indexOf('>', start + 2)
        Option.when(end >= 0)(end + 1)
      else
        None

  private def openTag(s: String, nameStart: Int): Option[Int] =
    var i = nameStart
    while i < s.length && isTagNameChar(s.charAt(i)) do
      i += 1
    var continue = true
    while continue do
      // An attribute needs leading whitespace
      var j = i
      while j < s.length && isWhite(s.charAt(j)) do
        j += 1
      attribute(s, j) match
        case Some(end) if j > i =>
          i = end
        case _ =>
          continue = false
    while i < s.length && isWhite(s.charAt(i)) do
      i += 1
    if s.startsWith("/>", i) then
      Some(i + 2)
    else if i < s.length && s.charAt(i) == '>' then
      Some(i + 1)
    else
      None

  private def attribute(s: String, start: Int): Option[Int] =
    if start >= s.length then
      None
    else
      val c0 = s.charAt(start)
      if !(c0 < 128 && (c0.isLetter || c0 == '_' || c0 == ':')) then
        None
      else
        var i = start + 1
        while i < s.length && {
            val c = s.charAt(i)
            c < 128 && (c.isLetterOrDigit || c == '_' || c == '.' || c == ':' || c == '-')
          }
        do
          i += 1
        // Optional value
        var j = i
        while j < s.length && isWhite(s.charAt(j)) do
          j += 1
        if j < s.length && s.charAt(j) == '=' then
          j += 1
          while j < s.length && isWhite(s.charAt(j)) do
            j += 1
          attributeValue(s, j) match
            case Some(end) =>
              Some(end)
            case None =>
              Some(i)
        else
          Some(i)

  private def attributeValue(s: String, start: Int): Option[Int] =
    if start >= s.length then
      None
    else
      s.charAt(start) match
        case q @ ('"' | '\'') =>
          val end = s.indexOf(q, start + 1)
          Option.when(end >= 0)(end + 1)
        case _ =>
          var i = start
          while i < s.length && {
              val c = s.charAt(i)
              c > ' ' && "\"'=<>`".indexOf(c) < 0
            }
          do
            i += 1
          Option.when(i > start)(i)

  private def closingTag(s: String, nameStart: Int): Option[Int] =
    if nameStart >= s.length || !(s.charAt(nameStart).isLetter && s.charAt(nameStart) < 128) then
      None
    else
      var i = nameStart
      while i < s.length && isTagNameChar(s.charAt(i)) do
        i += 1
      while i < s.length && isWhite(s.charAt(i)) do
        i += 1
      Option.when(i < s.length && s.charAt(i) == '>')(i + 1)

end HtmlSyntax
