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
package wvlet.uni.text.csv

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.JSONBoolean
import wvlet.uni.json.JSON.JSONNull
import wvlet.uni.json.JSON.JSONNumber
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.json.JSON.JSONString
import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.codec.CaseClassWeaver

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
  * Writes records and Weaver values as lines of text. Lines end with `\n`.
  */
private[csv] final class CSVWriter(format: CSVFormat):
  private val delimiter = format.delimiter
  private val quote     = format.quote
  private val style     = format.escapeStyle
  private val nullText  = format.nullString.getOrElse("")

  // Records have no missing values. When the null string is empty, an empty field is written bare
  // (`a,`), as other CSV tools do, instead of quoted (`a,""`)
  private def recordField(s: String): Option[String] =
    if s.isEmpty && nullText.isEmpty then
      None
    else
      Some(s)

  def writeRecords(records: IterableOnce[Seq[String]], out: StringBuilder): Unit = records
    .iterator
    .foreach(r => writeRecord(r.map(recordField), out))

  def writeRecordsTo(path: IOPath, records: IterableOnce[Seq[String]]): Unit =
    withFile(path) { (out, sb) =>
      records
        .iterator
        .foreach { r =>
          writeRecord(r.map(recordField), sb)
          flushIfFull(out, sb)
        }
    }

  def writeValues[A](values: IterableOnce[A], weaver: Weaver[A], out: StringBuilder): Unit =
    val it = values.iterator
    if it.hasNext then
      val first   = toObject(weaver, it.next())
      val columns = columnNames(weaver, first)
      if format.hasHeader then
        writeRecord(columns.map(Some(_)), out)
      writeObject(columns, first, out)
      it.foreach(v => writeObject(columns, toObject(weaver, v), out))

  def writeValuesTo[A](path: IOPath, values: IterableOnce[A], weaver: Weaver[A]): Unit =
    withFile(path) { (out, sb) =>
      val it = values.iterator
      if it.hasNext then
        val first   = toObject(weaver, it.next())
        val columns = columnNames(weaver, first)
        if format.hasHeader then
          writeRecord(columns.map(Some(_)), sb)
        writeObject(columns, first, sb)
        it.foreach { v =>
          writeObject(columns, toObject(weaver, v), sb)
          flushIfFull(out, sb)
        }
    }

  private def withFile(path: IOPath)(body: (OutputStream, StringBuilder) => Unit): Unit =
    val out = FileSystem.writeStream(path)
    try
      val sb = StringBuilder()
      body(out, sb)
      flush(out, sb)
    finally
      out.close()

  private def flushIfFull(out: OutputStream, sb: StringBuilder): Unit =
    if sb.length >= CSVFormat.fileChunkSize then
      flush(out, sb)

  private def flush(out: OutputStream, sb: StringBuilder): Unit =
    if sb.nonEmpty then
      out.write(sb.toString.getBytes(StandardCharsets.UTF_8))
      sb.clear()

  private def toObject[A](weaver: Weaver[A], v: A): JSONObject =
    JSON.parse(weaver.toJson(v)) match
      case o: JSONObject =>
        o
      case other =>
        throw IllegalArgumentException(
          s"Can't write ${other.toJSON} as a CSV row. Rows need an object type such as a case class"
        )

  private def columnNames[A](weaver: Weaver[A], first: JSONObject): IndexedSeq[String] =
    if format.columns.nonEmpty then
      format.columns.toIndexedSeq
    else
      weaver match
        case c: CaseClassWeaver[?] =>
          c.fieldNames
        case _ =>
          first.v.map(_._1).toIndexedSeq

  private def writeObject(columns: IndexedSeq[String], obj: JSONObject, out: StringBuilder): Unit =
    val fields = obj.v.toMap
    writeRecord(
      columns.map { c =>
        fields.get(c) match
          case None | Some(JSONNull()) =>
            None
          case Some(JSONString(s)) =>
            Some(s)
          case Some(b: JSONBoolean) =>
            Some(b.toJSON)
          case Some(n: JSONNumber) =>
            Some(n.toJSON)
          case Some(nested) =>
            Some(nested.toJSON)
      },
      out
    )

  /**
    * Write a record. `None` is a missing value, written as the null string.
    */
  private def writeRecord(fields: Seq[Option[String]], out: StringBuilder): Unit =
    var first = true
    fields.foreach { f =>
      if !first then
        out += delimiter
      first = false
      f match
        case None =>
          out ++= nullText
        case Some(s) =>
          writeField(escapeFormula(s), out, fields.size == 1)
    }
    if fields.size == 1 && fields.head.isEmpty && nullText.isEmpty then
      // A lone empty field would read back as a blank line, which readers skip
      out ++= s"${quote}${quote}"
    out += '\n'

  private def escapeFormula(s: String): String =
    if format.formulaEscape && s.nonEmpty && CSVWriter.isFormulaStart(s.charAt(0)) &&
      !CSVWriter.isNumber(s)
    then
      s"'${s}"
    else
      s

  private def writeField(s: String, out: StringBuilder, alone: Boolean): Unit =
    style match
      case CSVEscapeStyle.Quoted =>
        if needsQuotes(s, alone) then
          out += quote
          var i = 0
          while i < s.length do
            val c = s.charAt(i)
            if c == quote then
              out += quote
            out += c
            i += 1
          out += quote
        else
          out ++= s
      case CSVEscapeStyle.Backslash =>
        var i = 0
        while i < s.length do
          s.charAt(i) match
            case '\\' =>
              out ++= "\\\\"
            case '\n' =>
              out ++= "\\n"
            case '\r' =>
              out ++= "\\r"
            case '\t' =>
              out ++= "\\t"
            case c if c == delimiter =>
              out += '\\'
              out += c
            case c =>
              out += c
          i += 1
      case CSVEscapeStyle.Plain =>
        if s.exists(c => c == delimiter || c == '\n' || c == '\r') then
          throw IllegalArgumentException(
            s"Can't write a field that holds the delimiter or a line break without quotes: ${s}"
          )
        out ++= s

  private def needsQuotes(s: String, alone: Boolean): Boolean =
    if s.isEmpty then
      // An empty string differs from a missing value only when quoted
      format.nullString.contains("") || alone
    else
      format.nullString.contains(s) || s.charAt(0) == ' ' || s.charAt(s.length - 1) == ' ' ||
      s.exists(c => c == delimiter || c == quote || c == '\n' || c == '\r')

end CSVWriter

private[csv] object CSVWriter:
  private val numberPattern = """[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""".r

  def isFormulaStart(c: Char): Boolean =
    c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r'

  def isNumber(s: String): Boolean = numberPattern.matches(s)
