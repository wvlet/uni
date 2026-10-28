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
      val lastLine = src.leading.substring(src.leading.lastIndexOf('\n') + 1)
      val sep      =
        if lastLine.trim.isEmpty || root.source.raw.nonEmpty then
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
      // Keep the final line break when the root was empty
      if src.raw.endsWith("\n") && !w.sb.endsWith("\n") then
        w.sb.append(lb)
      w.sb.result()

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
      else
        n match
          case m: Mapping if isBlock(m) =>
            sb.append(n.source.opening)
            mapping(m, indentOf(m, col))
          case s: Sequence if isBlock(s) =>
            sb.append(n.source.opening)
            sequence(s, indentOf(s, col))
          case other =>
            sb.append(inlineText(other, col, flow = false, key = false))
            sb.append(n.source.comment)

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
          sb.append(valueLeading(v, ":", " ", indent + 2))
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
            sb.append(childLeading(item.source, first, i, indent, "- "))
          else
            sb.append(childLeading(YAMLSource.none, first, i, indent, ""))
            sb.append(valueLeading(item, "-", " ", indent + 2))
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
    private def valueLeading(v: YAMLNode, indicator: String, space: String, col: Int): String =
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
            indicator + space
          else
            indicator + p + lb + " " * col
      else if leading.nonEmpty then
        leading
      else if isEmptyPlain(v) then
        indicator
      else
        indicator + space

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
        case c if c < ' ' || c == '\u007f' || c == '\u0085' || c == ' ' || c == ' ' =>
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
