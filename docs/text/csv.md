# CSV and TSV

`wvlet.uni.text.csv` reads and writes CSV and TSV, and decodes rows into case classes with [Weaver](../core/weaver). It works on JVM, Scala.js, and Scala Native with no extra dependencies.

## Reading Rows

```scala
import wvlet.uni.text.csv.CSV
import wvlet.uni.weaver.Weaver

case class Person(name: String, age: Int, email: Option[String] = None) derives Weaver

val people = CSV.read[Person]("name,age,email\nAlice,30,alice@example.com\nBob,25,\n")
// Seq(Person(Alice, 30, Some(alice@example.com)), Person(Bob, 25, None))
```

Columns map to fields by the header names, in any order. Weaver converts each text value to the field's type, so `Int`, `Double`, `Boolean`, `LocalDate`, and `Instant` fields read as you would expect.

An unquoted empty field is a missing value. The field then takes its default value or `None`, and a missing required field is an error. A quoted `""` is an empty string. Use `withNullString("NA")` when the file marks missing values another way, or `noNullString()` to read every field as text.

`parse` returns every line as text, including the header:

```scala
CSV.parse("a,b\n1,\"x, y\"\n") // Seq(Vector(a, b), Vector(1, x, y))
```

## Streaming Large Files

`readFile` reads a file one row at a time, so a large export never has to fit in memory. The reader closes the file at the end of input or on an error. If you stop reading early, close it yourself:

```scala
import wvlet.uni.io.IOPath

val reader = CSV.readFile[Person](IOPath.parse("people.csv"))
try reader.filter(_.age >= 30).foreach(println)
finally reader.close()
```

`reader` streams from any `Iterator[String]` of chunks. Use `CSVReader.decodeUTF8` to read bytes, for example an object downloaded from S3 or an HTTP response body. A field, a quote, or a UTF-8 character may span chunks.

Files stream on the JVM and Native. On Node.js, `readFile` reads the whole file first, and in a browser it can't read files; pass the chunks to `reader` instead.

## Writing

```scala
CSV.write(Seq(Person("Alice", 30, Some("a@example.com")), Person("Bob", 25)))
// name,age,email
// Alice,30,a@example.com
// Bob,25,
```

The header comes from the field names. A field is quoted only when it has to be: when it holds the delimiter, a quote, or a line break, has a leading or trailing space, or would otherwise read back as a missing value. With `TSV` or `noQuote()`, a value that would read back as missing can't be written, and `write` throws `IllegalArgumentException`. Nested values (lists and objects) are written as JSON. Lines end with `\n`.

`writeRecords` writes lines of strings, and `writeFile` / `writeRecordsFile` write to a file in chunks.

When people will open the file in a spreadsheet, turn on `withFormulaEscape()`. A field that starts with `=`, `+`, `-`, or `@` then gets a leading `'`, so the spreadsheet doesn't run it as a formula. Numbers such as `-5` are written as they are.

## TSV

`TSV` follows the convention of PostgreSQL `COPY`, MySQL, and other database tools. Fields are never quoted. A tab, a line break, or a backslash inside a field is written as `\t`, `\n`, or `\\`, and `\N` is a missing value:

```scala
import wvlet.uni.text.csv.TSV

TSV.parse("name\tnote\nA\tline 1\\nline 2\nB\t\\N\n")
// Seq(Vector(name, note), Vector(A, line 1
// line 2), Vector(B, ""))
```

`TSV.quoted` reads TSV that is quoted like CSV, which is how spreadsheets save it.

## Other Dialects

Every setting has a `withXXX` method, and an optional one has a `noXXX` method too:

| Setting | Default | Notes |
|---------|---------|-------|
| `withDelimiter(';')` | `,` | The field separator |
| `withQuote('\'')` / `noQuote()` | `"` | `noQuote()` reads and writes fields as they are, with no escaping |
| `withEscapeStyle(...)` | `Quoted` | `Quoted` (RFC 4180), `Backslash` (TSV), or `Plain` |
| `withHeader()` / `noHeader()` | header | Without a header, a case class's fields map to columns by position |
| `withColumns("a", "b")` | from the header | Names the columns, replacing the header line if there is one |
| `withNullString("NA")` / `noNullString()` | unquoted empty | The text of a missing value |
| `withRaggedRows()` | off | Accepts rows with too few fields (treated as missing) or too many (ignored) |
| `withFormulaEscape()` | off | Escapes spreadsheet formulas on write |

The reader also accepts CRLF line endings, a leading byte order mark, and a file with no final line break, and it skips blank lines. In a one-column file, an empty line is a missing value instead.

## Errors

Malformed input throws `TextParseException`, with the line and column in the message:

```text
Unclosed quote (line 2, column 3)
Expected 2 fields, but found 3. Use withRaggedRows to accept rows of different lengths (line 2, column 1)
```

A row that can't be decoded into the target type is reported with its line too.

## Other CSV Libraries

On the JVM, [kantan.csv](https://nrinaudo.github.io/kantan.csv/), [scala-csv](https://github.com/tototoshi/scala-csv), [fs2-data](https://fs2-data.gnieh.org/) and Jackson's CSV module are mature choices, and they fit well in apps built on their ecosystems. `wvlet.uni.text.csv` is for code that wants the same CSV support on all three platforms, combined with Weaver and Uni's file system API.
