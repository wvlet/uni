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
import wvlet.uni.weaver.Weaver

/**
  * How a field that holds the delimiter, a line break, or the quote character is written
  */
enum CSVEscapeStyle:
  /**
    * RFC 4180: wrap the field in quotes and double each quote inside it (`"a ""b"", c"`)
    */
  case Quoted

  /**
    * Escape with backslashes (`\t`, `\n`, `\r`, `\\`) and never quote, as PostgreSQL `COPY`, MySQL,
    * and "linear TSV" do
    */
  case Backslash

  /**
    * No escaping. Fields can't hold the delimiter or a line break
    */
  case Plain

/**
  * The dialect of a CSV or TSV file, and the entry points to read and write it. [[CSV]] and [[TSV]]
  * are the common presets; derive others with the `withXXX` methods:
  *
  * {{{
  *   case class Person(name: String, age: Int) derives Weaver
  *
  *   CSV.read[Person]("name,age\nAlice,30\n")                 // Seq(Person(Alice, 30))
  *   CSV.withDelimiter(';').read[Person]("name;age\nBob;25")  // Seq(Person(Bob, 25))
  *   CSV.write(Seq(Person("Alice", 30)))                      // "name,age\nAlice,30\n"
  * }}}
  *
  * Parse errors throw [[wvlet.uni.text.parser.TextParseException]] with the line and column.
  *
  * @param delimiter
  *   the field separator
  * @param quote
  *   the quote character of [[CSVEscapeStyle.Quoted]]
  * @param escapeStyle
  *   how special characters inside fields are written
  * @param hasHeader
  *   whether the first line names the columns
  * @param columns
  *   column names to use instead of the header line, or for a file without one
  * @param nullString
  *   the raw field text that means a missing value, for decoding with Weaver. For
  *   [[CSVEscapeStyle.Quoted]] only an unquoted field matches, so a quoted `""` stays an empty
  *   string.
  * @param raggedRows
  *   accept rows whose field count differs from the first row. Missing fields are treated as null
  *   and extra fields are ignored.
  * @param formulaEscape
  *   prefix written fields that start with `=`, `+`, `-`, `@`, a tab, or a carriage return with
  *   `'`, so spreadsheets don't run them as formulas. Numbers like `-5` are left as they are.
  */
class CSVFormat(
    val delimiter: Char = ',',
    val quote: Char = '"',
    val escapeStyle: CSVEscapeStyle = CSVEscapeStyle.Quoted,
    val hasHeader: Boolean = true,
    val columns: Seq[String] = Seq.empty,
    val nullString: Option[String] = Some(""),
    val raggedRows: Boolean = false,
    val formulaEscape: Boolean = false
):
  require(delimiter != '\n' && delimiter != '\r', "The delimiter can't be a line break")
  require(
    escapeStyle != CSVEscapeStyle.Quoted || quote != delimiter,
    "The quote character must differ from the delimiter"
  )

  require(
    escapeStyle != CSVEscapeStyle.Backslash || delimiter != '\\',
    "The delimiter can't be a backslash when backslash escapes are used"
  )

  private def copy(
      delimiter: Char = delimiter,
      quote: Char = quote,
      escapeStyle: CSVEscapeStyle = escapeStyle,
      hasHeader: Boolean = hasHeader,
      columns: Seq[String] = columns,
      nullString: Option[String] = nullString,
      raggedRows: Boolean = raggedRows,
      formulaEscape: Boolean = formulaEscape
  ): CSVFormat = CSVFormat(
    delimiter,
    quote,
    escapeStyle,
    hasHeader,
    columns,
    nullString,
    raggedRows,
    formulaEscape
  )

  def withDelimiter(delimiter: Char): CSVFormat = copy(delimiter = delimiter)

  /**
    * Quote fields with the given character ([[CSVEscapeStyle.Quoted]])
    */
  def withQuote(quote: Char): CSVFormat = copy(quote = quote, escapeStyle = CSVEscapeStyle.Quoted)

  /**
    * Read and write fields as they are, with no quoting or escapes ([[CSVEscapeStyle.Plain]])
    */
  def noQuote(): CSVFormat = copy(escapeStyle = CSVEscapeStyle.Plain)

  def withEscapeStyle(style: CSVEscapeStyle): CSVFormat = copy(escapeStyle = style)

  def withHeader(hasHeader: Boolean = true): CSVFormat = copy(hasHeader = hasHeader)
  def noHeader(): CSVFormat                            = copy(hasHeader = false)

  /**
    * Name the columns in order. They replace the header line when the file has one.
    */
  def withColumns(columns: String*): CSVFormat = copy(columns = columns.toSeq)
  def noColumns(): CSVFormat                   = copy(columns = Seq.empty)

  def withNullString(nullString: String): CSVFormat = copy(nullString = Some(nullString))

  /**
    * Treat every field as a string, so no field reads as a missing value
    */
  def noNullString(): CSVFormat = copy(nullString = None)

  def withRaggedRows(enabled: Boolean = true): CSVFormat = copy(raggedRows = enabled)
  def noRaggedRows(): CSVFormat                          = copy(raggedRows = false)

  def withFormulaEscape(enabled: Boolean = true): CSVFormat = copy(formulaEscape = enabled)
  def noFormulaEscape(): CSVFormat                          = copy(formulaEscape = false)

  // Reading

  /**
    * Parse every line of the text into records, including the header line. Missing values are empty
    * strings.
    */
  def parse(text: String): Seq[IndexedSeq[String]] =
    val r = recordReader(Iterator.single(text))
    try r.toSeq
    finally r.close()

  /**
    * Read the rows of the text and decode each one into `A` with Weaver. With a header, columns map
    * to fields by name; without one, a case class's fields map to columns by position.
    */
  def read[A](text: String)(using weaver: Weaver[A]): Seq[A] =
    val r = reader[A](Iterator.single(text))
    try r.toSeq
    finally r.close()

  /**
    * Stream the records of text that arrives in chunks. A field may span chunks. The source is
    * closed at the end of input, on a parse error, or when the reader is closed.
    */
  def recordReader(chunks: Iterator[String]): CSVReader[IndexedSeq[String]] =
    val parser = CSVParser(this, chunks)
    CSVReader(
      Iterator.continually(parser.nextRecord()).takeWhile(_ != null).map(_.nn.valuesOrEmpty),
      () => CSVReader.closeSource(chunks)
    )

  /**
    * Stream rows decoded into `A` from text that arrives in chunks. For bytes, wrap them with
    * [[CSVReader.decodeUTF8]].
    */
  def reader[A](chunks: Iterator[String])(using weaver: Weaver[A]): CSVReader[A] =
    val parser = CSVParser(this, chunks)
    CSVReader(CSVDecoder(this, parser, weaver), () => CSVReader.closeSource(chunks))

  /**
    * Stream the rows of a UTF-8 file decoded into `A`. Files stream on the JVM and Native; on
    * Node.js the whole file is read first, and browsers can't read files.
    */
  def readFile[A](path: IOPath)(using weaver: Weaver[A]): CSVReader[A] = reader[A](
    CSVReader.decodeUTF8(FileSystem.readChunks(path, CSVFormat.fileChunkSize))
  )

  /**
    * Stream the records of a UTF-8 file, including the header line
    */
  def readFileRecords(path: IOPath): CSVReader[IndexedSeq[String]] = recordReader(
    CSVReader.decodeUTF8(FileSystem.readChunks(path, CSVFormat.fileChunkSize))
  )

  // Writing

  /**
    * Write records as text, one line each, adding quotes or escapes only where a field needs them
    */
  def writeRecords(records: IterableOnce[Seq[String]]): String =
    val sb = StringBuilder()
    CSVWriter(this).writeRecords(records, sb)
    sb.result()

  /**
    * Write values as rows with Weaver, after a header line of their field names (unless
    * [[noHeader]] is set). Missing values are written as the null string, and nested values as
    * JSON.
    */
  def write[A](values: IterableOnce[A])(using weaver: Weaver[A]): String =
    val sb = StringBuilder()
    CSVWriter(this).writeValues(values, weaver, sb)
    sb.result()

  /**
    * Write values to a UTF-8 file as [[write]] does, without building the whole text in memory
    */
  def writeFile[A](path: IOPath, values: IterableOnce[A])(using weaver: Weaver[A]): Unit =
    CSVWriter(this).writeValuesTo(path, values, weaver)

  /**
    * Write records to a UTF-8 file as [[writeRecords]] does
    */
  def writeRecordsFile(path: IOPath, records: IterableOnce[Seq[String]]): Unit = CSVWriter(this)
    .writeRecordsTo(path, records)

  override def toString: String =
    s"CSVFormat(delimiter=${delimiter}, escapeStyle=${escapeStyle}, hasHeader=${hasHeader})"

end CSVFormat

object CSVFormat:
  private[csv] val fileChunkSize: Int = 64 * 1024

/**
  * Comma-separated values as RFC 4180 defines them: quoted fields, `""` for a quote inside one, and
  * a header line. An unquoted empty field is a missing value.
  */
object CSV extends CSVFormat()

/**
  * Tab-separated values with backslash escapes (`\t`, `\n`, `\r`, `\\`) and `\N` for a missing
  * value, as PostgreSQL `COPY`, MySQL, and "linear TSV" write them. Fields are never quoted. For
  * TSV saved by a spreadsheet, which quotes like CSV, use [[TSV.quoted]].
  */
object TSV
    extends CSVFormat(
      delimiter = '\t',
      escapeStyle = CSVEscapeStyle.Backslash,
      nullString = Some("\\N")
    ):
  /**
    * TSV that quotes fields like CSV does, as spreadsheets save it
    */
  val quoted: CSVFormat = CSVFormat(delimiter = '\t')
