package wvlet.uni.json

import wvlet.uni.json.JSON.*

/**
  * Write JSON as block-style YAML that reads back as the same value:
  *
  * {{{
  *   YAMLFormatter.toYaml("""{"name":"api","ports":[80,443],"tags":[]}""")
  *   // name: api
  *   // ports:
  *   //   - 80
  *   //   - 443
  *   // tags: []
  * }}}
  *
  * Words are written plain unless YAML would read them as a number, boolean, or null; other
  * one-line text is single-quoted, multi-line text is a literal block (`|` or `|-`), and text with
  * control characters is double-quoted with escapes. Empty objects and arrays are written as `{}`
  * and `[]`.
  */
object YAMLFormatter:
  def toYaml(json: String): String = toYaml(JSON.parseAny(json))

  def toYaml(value: JSONValue): String =
    val lines = Seq.newBuilder[String]
    value match
      case c if isNonEmptyCollection(c) =>
        block(c, 0, lines)
      case other =>
        val (head, rest) = scalar(other, 1)
        lines += head
        lines ++= rest
    val out = lines.result()
    // A `|` block keeps its final line break only if the text has one after the block's last line
    if lastScalarIsClipBlock(value) then
      out.mkString("", "\n", "\n")
    else
      out.mkString("\n")

  /** Is the last scalar of the value a literal block with `|`, which ends in a line break? */
  private def lastScalarIsClipBlock(v: JSONValue): Boolean =
    v match
      case o: JSONObject =>
        o.v.lastOption.exists((_, child) => lastScalarIsClipBlock(child))
      case a: JSONArray =>
        a.v.lastOption.exists(lastScalarIsClipBlock)
      case s: JSONString =>
        literalBlock(s.v, 0).exists(_._1 == "|")
      case _ =>
        false

  private def pad(indent: Int): String = "  " * indent

  private def isNonEmptyCollection(v: JSONValue): Boolean =
    v match
      case o: JSONObject =>
        o.v.nonEmpty
      case a: JSONArray =>
        a.v.nonEmpty
      case _ =>
        false

  /** The lines of a non-empty object or array whose entries start at `indent` */
  private def block(v: JSONValue, indent: Int, lines: collection.mutable.Builder[String, ?]): Unit =
    v match
      case o: JSONObject =>
        o.v
          .foreach { (k, child) =>
            val key = quote(k)
            if isNonEmptyCollection(child) then
              lines += s"${pad(indent)}${key}:"
              block(child, indent + 1, lines)
            else
              val (head, rest) = scalar(child, indent + 1)
              lines += s"${pad(indent)}${key}: ${head}"
              lines ++= rest
          }
      case a: JSONArray =>
        a.v
          .foreach { child =>
            if isNonEmptyCollection(child) then
              // Write the child one level deeper, then put `- ` in place of its first indent
              val nested = Seq.newBuilder[String]
              block(child, indent + 1, nested)
              val childLines = nested.result()
              lines += s"${pad(indent)}- ${childLines.head.drop(pad(indent + 1).length)}"
              lines ++= childLines.tail
            else
              val (head, rest) = scalar(child, indent + 1)
              lines += s"${pad(indent)}- ${head}"
              lines ++= rest
          }
      case _ =>

  /**
    * A scalar or an empty collection: the text after `key: ` or `- `, and the lines of a literal
    * block indented at `indent`
    */
  private def scalar(v: JSONValue, indent: Int): (String, Seq[String]) =
    v match
      case _: JSONObject =>
        ("{}", Nil)
      case _: JSONArray =>
        ("[]", Nil)
      case s: JSONString =>
        literalBlock(s.v, indent).getOrElse((quote(s.v), Nil))
      case other =>
        (other.toJSON, Nil)

  /** Words YAML 1.1 or 1.2 readers resolve to something other than a string */
  private val reservedWords = Set(
    "null",
    "true",
    "false",
    "yes",
    "no",
    "on",
    "off",
    "y",
    "n",
    "<<",
    "="
  )

  // Characters that start something other than a plain scalar, plus the starts of numbers (digits,
  // signs, and `.` for .inf and .nan)
  private val unsafeFirst = "-?:,[]{}#&*!|>'\"%@`~+.0123456789"

  /**
    * Can the text be written plain (unquoted) and read back as the same string? It must not start
    * with an indicator or look like a number, contain `: ` or ` #`, or have spaces at either end.
    */
  private def isPlain(s: String): Boolean =
    s.nonEmpty && unsafeFirst.indexOf(s.head) < 0 && s.forall(isPrintable) && s.head != ' ' &&
      s.last != ' ' && s.last != ':' && !s.contains(": ") && !s.contains(" #") &&
      !reservedWords.contains(s.toLowerCase)

  /** Characters that every quoting style except double quotes can hold as they are */
  private def isPrintable(c: Char): Boolean =
    c >= ' ' && c != '\u007f' &&
      !(c >= '\u0080' && c <= '\u009f') && !Character.isSurrogate(c) && c != '﻿' && c != '￾' &&
      c != '￿'

  /** A key or one-line string: plain, single-quoted, or double-quoted */
  private def quote(s: String): String =
    if isPlain(s) then
      s
    else if s.forall(isPrintable) then
      s"'${s.replace("'", "''")}'"
    else
      doubleQuoted(s)

  private def doubleQuoted(s: String): String =
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"' =>
          sb.append("\\\"")
        case '\\' =>
          sb.append("\\\\")
        case '\n' =>
          sb.append("\\n")
        case '\r' =>
          sb.append("\\r")
        case '\t' =>
          sb.append("\\t")
        case c
            if Character.isHighSurrogate(c) && i + 1 < s.length &&
              Character.isLowSurrogate(s.charAt(i + 1)) =>
          sb.append(c).append(s.charAt(i + 1))
          i += 1
        case c if isPrintable(c) =>
          sb.append(c)
        case c =>
          wvlet.uni.msgpack.spi.Value.escapeChar(sb, c)
      i += 1
    sb.append('"')
    sb.result()

  /**
    * Multi-line text as a literal block, when it can be one: printable lines, at most one final
    * line break, and a first non-empty line that does not start with a space (which would be read
    * as the block's indentation)
    */
  private def literalBlock(s: String, indent: Int): Option[(String, Seq[String])] =
    val body =
      if s.endsWith("\n") then
        s.dropRight(1)
      else
        s
    val lines = body.split("\n", -1).toSeq
    val ok    =
      body.contains('\n') && !body.endsWith("\n") &&
        lines.forall(l => l.forall(c => isPrintable(c) || c == '\t')) &&
        lines.find(_.nonEmpty).exists(l => !l.startsWith(" ") && !l.startsWith("\t")) &&
        !lines.exists(l => l.nonEmpty && l.isBlank)
    if !ok then
      None
    else
      val header =
        if s.endsWith("\n") then
          "|"
        else
          "|-"
      Some(
        header ->
          lines.map { l =>
            if l.isEmpty then
              ""
            else
              s"${pad(indent)}${l}"
          }
      )

end YAMLFormatter
