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

/**
  * Writes [[YAMLDocument]]s back to text. Unchanged nodes print their source text; changed nodes
  * print in block style with two-space indentation, keeping the text around them (the comments
  * above a mapping key, the comment after a value) and their recorded scalar style.
  */
private[yaml] object YAMLWriter:
  import YAMLNode.*

  def write(doc: YAMLDocument): String =
    val src = doc.source
    if YAMLSource.same(src.parsed, Tuple1(doc.root)) then
      src.raw
    else
      val lb   = lineBreak(src.raw)
      val w    = Writer(lb)
      val root = doc.root
      // Separate a new root from a `---` marker or a comment before it
      val lastLine  = src.leading.substring(src.leading.lastIndexOf('\n') + 1).stripPrefix("\uFEFF")
      val emptyRoot =
        root match
          case Scalar("", ScalarStyle.Plain, None, None, _) =>
            true
          case _ =>
            false
      val sep =
        if lastLine.trim.isEmpty || root.source.raw.nonEmpty || emptyRoot then
          ""
        else if w.isBlock(root) || lastLine.contains('#') then
          lb
        else if lastLine.endsWith(" ") then
          ""
        else
          " "
      w.sb.append(src.leading).append(sep)
      if w.isBlock(root) && root.source.raw.isEmpty && hasProps(root) then
        w.sb.append(props(root)).append(lb)
      w.node(root, 0)
      w.sb.append(src.comment)
      // Keep the final line break when the root was empty, or a block scalar at the end needs it
      if src.raw.endsWith("\n") && !w.sb.endsWith("\n") || w.breakAt == w.sb.length then
        w.sb.append(lb)
      w.sb.result()
    end if

  end write

  def writeStream(docs: Seq[YAMLDocument]): String =
    val lb =
      if docs.exists(_.source.raw.contains("\r\n")) then
        "\r\n"
      else
        "\n"
    val sb   = StringBuilder()
    var prev = ""
    docs.foreach { d =>
      val text = d.toYaml
      if sb.nonEmpty then
        if !sb.endsWith("\n") then
          sb.append(lb)
        if !endsWithMarker(prev) then
          // Directives after a document need a `...` line to end that document
          if hasDirective(d.source.leading) then
            sb.append("...").append(lb)
          else if !startsWithMarker(d.source.leading) then
            sb.append("---").append(lb)
      sb.append(text)
      prev = text
    }
    sb.result()

  /**
    * The offset of the first line break (`\n` or `\r\n`) in the text, or -1
    */
  def lineBreakAt(s: String): Int =
    val i = s.indexOf('\n')
    if i > 0 && s.charAt(i - 1) == '\r' then
      i - 1
    else
      i

  /**
    * The offset of the first comma outside comments, or -1
    */
  private def separatorAt(s: String): Int =
    var i         = 0
    var inComment = false
    var found     = -1
    while found < 0 && i < s.length do
      s.charAt(i) match
        case '#' =>
          inComment = true
        case '\n' =>
          inComment = false
        case ',' if !inComment =>
          found = i
        case _ =>
      i += 1
    found

  def hasSeparator(s: String): Boolean = separatorAt(s) >= 0

  /**
    * Remove the comma separating flow collection children from the text between them
    */
  def removeSeparator(s: String): String =
    separatorAt(s) match
      case -1 =>
        s
      case i =>
        s.substring(0, i) + s.substring(i + 1)

  /**
    * The text before a comma on the same line as the previous child, such as the space in `1 , 2`
    */
  def beforeSeparator(s: String): String =
    separatorAt(s) match
      case -1 =>
        ""
      case i =>
        val pre = s.substring(0, i)
        if lineBreakAt(pre) < 0 then
          pre
        else
          ""

  /**
    * The text between flow collection children that belongs to the next one: after the comma, and
    * from the first line break, since the rest of the previous line belongs to the previous child
    */
  def ownText(s: String): String =
    val pre = beforeSeparator(s)
    val t   =
      if hasSeparator(s) && lineBreakAt(pre) < 0 && separatorAt(s) == pre.length then
        s.substring(pre.length + 1)
      else
        removeSeparator(s)
    lineBreakAt(t) match
      case -1 =>
        t
      case i =>
        t.substring(i)

  /**
    * The rest of the previous child's line in the text between flow collection children, after its
    * comma, or None when the text has no line break
    */
  def lineRest(s: String): Option[String] =
    lineBreakAt(s) match
      case -1 =>
        None
      case i =>
        val line = s.substring(0, i)
        separatorAt(line) match
          case -1 =>
            Some(line)
          case j =>
            Some(line.substring(j + 1))

  private def lines(text: String): Seq[String] = text.split("\n", -1).map(_.stripSuffix("\r")).toSeq

  private def isMarker(line: String, marker: String): Boolean =
    line.startsWith(marker) && (line.length == 3 || YAMLParser.isSeparator(line.charAt(3)))

  /**
    * Does the text before a document's root have a `---` marker?
    */
  private def startsWithMarker(leading: String): Boolean = lines(leading).exists(isMarker(_, "---"))

  /**
    * Does the text before a document's root have a directive (`%YAML`, `%TAG`) before its `---`?
    */
  private def hasDirective(leading: String): Boolean = lines(leading)
    .takeWhile(!isMarker(_, "---"))
    .exists(_.stripPrefix("﻿").startsWith("%"))

  /**
    * Does the text end with a `...` line, followed only by blank lines and comments?
    */
  private def endsWithMarker(text: String): Boolean = lines(text)
    .reverse
    .find(l => l.trim.nonEmpty && !l.trim.startsWith("#"))
    .exists(isMarker(_, "..."))

  private def lineBreak(raw: String): String =
    if raw.contains("\r\n") then
      "\r\n"
    else
      "\n"

  private def hasProps(n: YAMLNode): Boolean = n.anchorOf.isDefined || n.tagOf.isDefined

  private def props(n: YAMLNode): String = (n.anchorOf.map("&" + _) ++ n.tagOf).mkString(" ")

  private class Writer(lb: String):
    val sb = StringBuilder()

    def isBlock(n: YAMLNode): Boolean =
      n match
        case m: Mapping =>
          !m.flow && m.entries.nonEmpty
        case s: Sequence =>
          !s.flow && s.items.nonEmpty
        case _ =>
          false

    /**
      * Write a node in block context, after its leading text. `col` is the column for the children
      * of a new block collection.
      */
    def node(n: YAMLNode, col: Int): Unit =
      if n.unchanged then
        sb.append(n.source.raw).append(n.source.comment)
        markBreak(n, n.source.raw)
      else
        n match
          case m: Mapping if isBlock(m) =>
            sb.append(n.source.opening)
            mapping(m, indentOf(m, col))
          case s: Sequence if isBlock(s) =>
            sb.append(n.source.opening)
            sequence(s, indentOf(s, col))
          case other =>
            val text = inlineText(other, col, flow = false, key = false)
            sb.append(text)
            sb.append(n.source.comment)
            markBreak(n, text)

    /**
      * The end of the last block scalar whose value ends with a line break. That line break comes
      * from the text after the scalar, so the document has to end with one when nothing follows.
      */
    var breakAt = -1

    private def markBreak(n: YAMLNode, text: String): Unit =
      n match
        case Scalar(v, ScalarStyle.Literal | ScalarStyle.Folded, _, _, _)
            if v.endsWith("\n") && text.contains('\n') =>
          // A block scalar is written over several lines; a quoted fallback is not
          breakAt = sb.length
        case _ =>

    private def indentOf(n: YAMLNode, col: Int): Int =
      if n.source.indent >= 0 then
        n.source.indent
      else
        col

    private def mapping(m: Mapping, indent: Int): Unit =
      val first = firstChild(m.source).collect { case e: YAMLEntry =>
        e.key.source
      }
      m.entries
        .zipWithIndex
        .foreach { (e, i) =>
          sb.append(childLeading(e.key.source, first, i, indent, ""))
          sb.append(keyText(e.key))
          val v = e.value
          sb.append(valueLeading(v, ":", indent + 2))
          node(v, indent + 2)
        }

    private def sequence(s: Sequence, indent: Int): Unit =
      val first = firstChild(s.source).collect { case n: YAMLNode =>
        n.source
      }
      s.items
        .zipWithIndex
        .foreach { (item, i) =>
          if item.source.leading.nonEmpty then
            // The leading text of a parsed item includes its dash
            sb.append(spaced(childLeading(item.source, first, i, indent, "- "), item))
          else
            sb.append(childLeading(YAMLSource.none, first, i, indent, ""))
            sb.append(valueLeading(item, "-", indent + 2))
          node(item, indent + 2)
        }

    /**
      * The first child recorded when a collection was parsed
      */
    private def firstChild(src: YAMLSource): Option[Any] =
      src.parsed match
        case p: Product if p.productArity > 0 =>
          p.productElement(0) match
            case s: Seq[?] =>
              s.headOption
            case _ =>
              None
        case _ =>
          None

    /**
      * The leading text of a mapping key or a sequence item at position `i`
      */
    private def childLeading(
        src: YAMLSource,
        first: Option[YAMLSource],
        i: Int,
        indent: Int,
        marker: String
    ): String =
      val wasFirst = first.exists(_ eq src)
      val leading  = src.leading
      if leading.isEmpty && marker.isEmpty then
        if i == 0 then
          ""
        else
          lb + " " * indent
      else if i == 0 then
        if wasFirst || src.isEmpty then
          leading
        else
          // A child moved to the front: drop the line break before it, keeping comments above it
          val lines = leading.split("\n", -1).drop(1).dropWhile(_.trim.isEmpty)
          lines.mkString("\n").stripLeading()
      else if wasFirst then
        lb + " " * indent + leading
      else
        leading

    /**
      * The text between a mapping key (or a sequence dash) and a value written after it
      */
    private def valueLeading(v: YAMLNode, indicator: String, col: Int): String =
      val leading = v.source.leading
      if v.unchanged then
        leading
      else if isBlock(v) then
        if v.source.raw.nonEmpty then
          leading
        else
          val p =
            if hasProps(v) then
              " " + props(v)
            else
              ""
          // A sequence under a sequence item can start on the same line
          if indicator == "-" && !hasProps(v) then
            indicator + " "
          else
            indicator + p + lb + " " * col
      else if leading.nonEmpty then
        spaced(leading, v)
      else if isEmptyPlain(v) then
        indicator
      else
        indicator + " "

    /**
      * Add a space after the leading text of an empty value (`key:`, `-`) that now has content
      */
    private def spaced(leading: String, v: YAMLNode): String =
      if !v.unchanged && !isEmptyPlain(v) && (leading.endsWith(":") || leading.endsWith("-")) then
        leading + " "
      else
        leading

    private def isEmptyPlain(v: YAMLNode): Boolean =
      v match
        case Scalar("", ScalarStyle.Plain, None, None, _) =>
          true
        case _ =>
          false

    private def keyText(k: YAMLNode): String =
      if k.unchanged then
        k.source.raw
      else
        inlineText(k, 0, flow = false, key = true)

    /**
      * Write a scalar, an alias, or a collection in flow style
      */
    def inlineText(n: YAMLNode, col: Int, flow: Boolean, key: Boolean): String =
      if n.unchanged then
        n.source.raw
      else if isBracketed(n) then
        flowCollection(n, col)
      else if isImplicitPair(n) then
        flowEntry(n.asInstanceOf[Mapping].entries.head, col)
      else
        val body =
          n match
            case s: Scalar =>
              scalar(s, col, flow || key, key)
            case Alias(name, _) =>
              "*" + name
            case m: Mapping =>
              m.entries
                .map { e =>
                  val k = inlineText(e.key, col, flow = true, key = true)
                  if isEmptyPlain(e.value) then
                    k
                  else
                    k + ": " + inlineText(e.value, col, flow = true, key = false)
                }
                .mkString("{", ", ", "}")
            case s: Sequence =>
              s.items.map(inlineText(_, col, flow = true, key = false)).mkString("[", ", ", "]")
        val p = props(n)
        if p.isEmpty then
          body
        else if body.isEmpty then
          p
        else
          p + " " + body

    /**
      * Is the node a parsed flow collection with brackets, whose text between children was
      * recorded?
      */
    private def isBracketed(n: YAMLNode): Boolean =
      val opening = n.source.opening
      n match
        case m: Mapping =>
          m.flow && opening.endsWith("{")
        case s: Sequence =>
          s.flow && opening.endsWith("[")
        case _ =>
          false

    /**
      * Write a mapping entry inside a flow collection, keeping the text between its key and value
      */
    private def flowEntry(e: YAMLEntry, col: Int): String =
      val k    = inlineText(e.key, col, flow = true, key = true)
      val v    = e.value
      val text =
        if v.unchanged then
          v.source.leading + v.source.raw
        else if isEmptyPlain(v) then
          v.source.leading
        else
          val leading =
            if v.source.leading.isEmpty then
              ": "
            else
              spaced(v.source.leading, v)
          leading + inlineText(v, col, flow = true, key = false)
      k + text

    /**
      * Is the node a single-pair mapping in a flow sequence (`[a: 1]`), which has no braces?
      */
    private def isImplicitPair(n: YAMLNode): Boolean =
      n match
        case m: Mapping =>
          m.flow && !m.source.isEmpty && m.source.opening.isEmpty && m.entries.size == 1 &&
          !hasProps(m)
        case _ =>
          false

    /**
      * Write a changed flow collection, keeping the text between its children: separators, line
      * breaks, and comments. As in block collections, the comment lines before a child belong to
      * it, and so does the comment on its line after it.
      */
    private def flowCollection(n: YAMLNode, col: Int): String =
      val src = n.source
      // (the leading text of each child, its text, the rest of its line)
      val children: Seq[(String, String, String)] =
        n match
          case m: Mapping =>
            m.entries.map(e => (e.key.source.leading, flowEntry(e, col), e.value.source.comment))
          case s: Sequence =>
            s.items
              .map(i =>
                (i.source.leading, inlineText(i, col, flow = true, key = false), i.source.comment)
              )
          case _ =>
            Nil
      val parsed: Seq[String] =
        src.parsed match
          case p: Product =>
            p.productElement(0) match
              case s: Seq[?] =>
                s.collect {
                  case e: YAMLEntry =>
                    e.key.source.leading
                  case c: YAMLNode =>
                    c.source.leading
                }
              case _ =>
                Nil
          case _ =>
            Nil
      // The text before the first child, when it has no comments
      val firstLeading = parsed.headOption.filter(_.trim.isEmpty).getOrElse("")
      // New children follow the layout of the last separator, without its comments
      val separator = parsed
        .drop(1)
        .lastOption
        .map { l =>
          val own = ownText(l)
          if own.contains('\n') then
            lb + own.substring(own.lastIndexOf('\n') + 1)
          else
            own
        }
        .getOrElse(" ")
      val indent = separator.substring(separator.lastIndexOf('\n') + 1)

      def leadingOf(leading: String, i: Int): String =
        if leading.isEmpty then
          // A new child
          if i == 0 then
            firstLeading
          else
            separator
        else
          // Only the first child has no comma before it, and all of its text belongs to it
          val wasFirst = !hasSeparator(leading)
          val own      =
            if wasFirst then
              leading
            else
              ownText(leading)
          if i == 0 then
            if own.trim.isEmpty then
              firstLeading
            else
              own
          else if own.trim.isEmpty && lineBreakAt(own) < 0 then
            separator
          else if wasFirst && lineBreakAt(own) != 0 then
            " " + own.stripLeading()
          else
            own

      val sb            = StringBuilder()
      val (anchor, tag) =
        src.parsed match
          case p: Product if p.productArity >= 4 =>
            (p.productElement(2), p.productElement(3))
          case _ =>
            (None, None)
      if anchor == n.anchorOf && tag == n.tagOf then
        sb.append(src.opening)
      else
        val p = props(n)
        if p.nonEmpty then
          sb.append(p).append(' ')
        sb.append(src.opening.last)

      // Write the rest of a child's line, and a line break before the next text after a comment
      def lineEnd(rest: String, next: String): String =
        if rest.contains('#') then
          if lineBreakAt(next) == 0 then
            rest + next
          else
            rest + lb + indent + next.stripLeading()
        else if lineBreakAt(next) == 0 then
          rest + next
        else
          next

      var rest = ""
      children
        .zipWithIndex
        .foreach { case ((leading, text, lineRest), i) =>
          if i > 0 then
            sb.append(beforeSeparator(leading)).append(',')
          sb.append(lineEnd(rest, leadingOf(leading, i))).append(text)
          rest = lineRest
        }
      val closing = src.closing
      if hasSeparator(closing) && children.nonEmpty then
        sb.append(beforeSeparator(closing)).append(',')
      // Without parsed children, the first line of the closing text belongs to no child
      val closingOwn =
        if parsed.isEmpty then
          closing
        else
          ownText(closing)
      sb.append(lineEnd(rest, closingOwn))
      sb.append(
        if src.opening.endsWith("[") then
          "]"
        else
          "}"
      )
      sb.result()

    end flowCollection

    private def scalar(s: Scalar, col: Int, flow: Boolean, key: Boolean): String =
      val v = s.value
      s.style match
        case ScalarStyle.Plain if isPlainSafe(v, flow) || v.isEmpty && !key =>
          v
        case ScalarStyle.SingleQuoted if !v.exists(c => c == '\n' || c < ' ' && c != '\t') =>
          "'" + v.replace("'", "''") + "'"
        case ScalarStyle.Literal | ScalarStyle.Folded if !flow =>
          blockScalar(v, s.style, col.max(2)).getOrElse(doubleQuoted(v))
        case _ =>
          doubleQuoted(v)

    /**
      * Write a literal (|) or folded (>) block scalar with its content at column `indent`, or None
      * when the value cannot be written that way
      */
    private def blockScalar(v: String, style: ScalarStyle, indent: Int): Option[String] =
      val body         = v.stripSuffix("\n")
      val lines        = body.split("\n", -1)
      val firstContent = lines.find(_.nonEmpty)
      if firstContent.isEmpty || firstContent.exists(l => l.head == ' ' || l.head == '\t') ||
        v.exists(c => c == '\r' || c < ' ' && c != '\t' && c != '\n')
      then
        None
      else
        val chomp =
          if !v.endsWith("\n") then
            "-"
          else if v.endsWith("\n\n") then
            "+"
          else
            ""
        // A folded scalar folds single line breaks, so write multi-line text as literal
        val header =
          if style == ScalarStyle.Folded && lines.length == 1 then
            ">"
          else
            "|"
        val text =
          lines
            .map { l =>
              if l.isEmpty then
                lb
              else
                lb + " " * indent + l
            }
            .mkString
        // With keep (+) chomping, the line break after the last empty line comes from the text that
        // follows, as with any node
        Some(header + chomp + text)
      end if

    end blockScalar

    private def doubleQuoted(v: String): String =
      val b = StringBuilder("\"")
      v.foreach {
        case '"' =>
          b.append("\\\"")
        case '\\' =>
          b.append("\\\\")
        case '\n' =>
          b.append("\\n")
        case '\t' =>
          b.append("\\t")
        case '\r' =>
          b.append("\\r")
        case c if c < ' ' || c == '\u007f' || c == '\u0085' || c == '\u2028' || c == '\u2029' =>
          b.append(f"\\u${c.toInt}%04x")
        case c =>
          b.append(c)
      }
      b.append('"').result()

  end Writer

  /**
    * Can the text be written as a plain scalar that reads back as the same text?
    */
  def isPlainSafe(s: String, flow: Boolean): Boolean =
    import YAMLParser.isSeparator
    def isFlowChar(c: Char) = ",[]{}".indexOf(c) >= 0
    s.nonEmpty && s.trim == s && !s.exists(c => c < ' ' || c == '\u007f') && !s.contains(": ") &&
    !s.endsWith(":") && !s.contains(" #") && !s.startsWith("---") && !s.startsWith("...") && {
      val c = s.head
      if "-?:".indexOf(c) >= 0 then
        s.length > 1 && !isSeparator(s.charAt(1)) && !(flow && isFlowChar(s.charAt(1)))
      else
        "@`%|>,[]{}#&*!\"'".indexOf(c) < 0
    } &&
    !(flow && s.exists(c => isFlowChar(c) || c == ':'))

  /**
    * Build a node from a JSON value. Strings are plain when they read back as the same string,
    * literal blocks when they span lines, and double-quoted otherwise.
    */
  def fromJSON(v: JSONValue): YAMLNode =
    v match
      case JSONObject(entries) =>
        Mapping(entries.map((k, x) => YAMLEntry(key(k), fromJSON(x))))
      case JSONArray(items) =>
        Sequence(items.map(fromJSON))
      case JSONString(s) =>
        Scalar(s, stringStyle(s))
      case JSONNull() =>
        Scalar("null")
      case JSONDouble(d) if d.isNaN =>
        Scalar(".nan")
      case JSONDouble(d) if d.isInfinite =>
        Scalar(
          if d > 0 then
            ".inf"
          else
            "-.inf"
        )
      case other =>
        Scalar(other.toJSON)

  def key(k: String): YAMLNode = Scalar(
    k,
    if isPlainSafe(k, flow = false) then
      ScalarStyle.Plain
    else
      ScalarStyle.DoubleQuoted
  )

  def stringStyle(s: String): ScalarStyle =
    if isPlainSafe(s, flow = false) && YAMLParser.resolvePlain(s).isInstanceOf[JSONString] then
      ScalarStyle.Plain
    else if s.contains('\n') then
      ScalarStyle.Literal
    else
      ScalarStyle.DoubleQuoted

end YAMLWriter
