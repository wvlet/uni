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

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.test.UniTest
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.codec.JSONValueWeaver

import java.nio.charset.StandardCharsets

case class CSVPerson(name: String, age: Int, email: Option[String] = None) derives Weaver
case class CSVFlags(id: Long, active: Boolean = true, score: Double = 0.0) derives Weaver
case class CSVNested(id: Int, tags: Seq[String]) derives Weaver
case class CSVNote(name: String, note: Option[String]) derives Weaver
case class CSVRow(a: String, b: Option[String]) derives Weaver

class CSVTest extends UniTest:

  test("parse RFC 4180 fields") {
    CSV.parse("a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n") shouldBe
      Seq(IndexedSeq("a", "b", "c"), IndexedSeq("1", "x, y", "say \"hi\""))
  }

  test("keep line breaks inside quoted fields") {
    CSV.parse("a,b\n\"line1\nline2\",\"x\r\ny\"\n") shouldBe
      Seq(IndexedSeq("a", "b"), IndexedSeq("line1\nline2", "x\r\ny"))
  }

  test("accept CRLF, a lone CR, a BOM, and no final line break") {
    CSV.parse("﻿a,b\r\n1,2\r3,4") shouldBe
      Seq(IndexedSeq("a", "b"), IndexedSeq("1", "2"), IndexedSeq("3", "4"))
  }

  test("skip blank lines but keep empty fields") {
    CSV.parse("a,b\n\n1,\n\r\n,2\n") shouldBe
      Seq(IndexedSeq("a", "b"), IndexedSeq("1", ""), IndexedSeq("", "2"))
    CSV.parse("") shouldBe Seq.empty
  }

  test("keep spaces around fields") {
    CSV.parse(" a , b \n") shouldBe Seq(IndexedSeq(" a ", " b "))
  }

  test("decode rows into case classes by header name") {
    CSV.read[CSVPerson]("age,name,email\n30,Alice,a@example.com\n25,Bob,\n") shouldBe
      Seq(CSVPerson("Alice", 30, Some("a@example.com")), CSVPerson("Bob", 25))
  }

  test("treat an unquoted empty field as missing and a quoted one as an empty string") {
    CSV.read[CSVPerson]("name,age,email\nA,1,\"\"\nB,2,\n") shouldBe
      Seq(CSVPerson("A", 1, Some("")), CSVPerson("B", 2, None))
    CSV.read[CSVFlags]("id,active,score\n1,,\n2,false,1.5\n") shouldBe
      Seq(CSVFlags(1), CSVFlags(2, false, 1.5))
  }

  test("report a missing required value with its line") {
    val e = intercept[TextParseException] {
      CSV.read[CSVPerson]("name,age\nA,1\nB,\n")
    }
    e.getMessage shouldContain "line 3"
    e.getMessage shouldContain "age"
  }

  test("use a custom null string or none") {
    CSV.withNullString("NA").read[CSVPerson]("name,age,email\nA,1,NA\nB,2,\"NA\"\n") shouldBe
      Seq(CSVPerson("A", 1), CSVPerson("B", 2, Some("NA")))
    CSV.noNullString().read[CSVPerson]("name,age,email\nA,1,\n") shouldBe
      Seq(CSVPerson("A", 1, Some("")))
  }

  test("map columns by position without a header") {
    CSV.noHeader().read[CSVPerson]("Alice,30,\nBob,25,b@example.com\n") shouldBe
      Seq(CSVPerson("Alice", 30), CSVPerson("Bob", 25, Some("b@example.com")))
    CSV.noHeader().withColumns("age", "name").read[CSVPerson]("30,Alice\n") shouldBe
      Seq(CSVPerson("Alice", 30))
  }

  test("read rows as JSON objects") {
    CSV.read[JSONValue]("a,b\n1,\n")(using JSONValueWeaver).map(_.toJSON) shouldBe
      Seq("""{"a":"1"}""")
  }

  test("reject malformed input with the line and column") {
    val unclosed = intercept[TextParseException] {
      CSV.parse("a,b\n1,\"open\n2,3\n")
    }
    unclosed.getMessage shouldContain "Unclosed quote (line 2, column 3)"

    val stray = intercept[TextParseException] {
      CSV.parse("a,b\n1,x\"y\n")
    }
    stray.getMessage shouldContain "line 2, column 4"

    val after = intercept[TextParseException] {
      CSV.parse("a,b\n\"x\"y,1\n")
    }
    after.getMessage shouldContain "after the closing quote (line 2, column 4)"
  }

  test("reject rows of a different length unless ragged rows are allowed") {
    val e = intercept[TextParseException] {
      CSV.parse("a,b\n1,2,3\n")
    }
    e.getMessage shouldContain "Expected 2 fields, but found 3"
    e.getMessage shouldContain "(line 2, column 1)"
    CSV.withRaggedRows().read[CSVPerson]("name,age,email\nA,1\nB,2,b,extra\n") shouldBe
      Seq(CSVPerson("A", 1), CSVPerson("B", 2, Some("b")))
  }

  test("reject empty or duplicate header names") {
    val dup = intercept[TextParseException] {
      CSV.read[CSVPerson]("name,name\nA,B\n")
    }
    dup.getMessage shouldContain "duplicate column name 'name' (line 1)"
    val empty = intercept[TextParseException] {
      CSV.read[CSVPerson]("name,,age\nA,x,1\n")
    }
    empty.getMessage shouldContain "empty name for column 2"
  }

  test("use other delimiters and quotes") {
    CSV.withDelimiter(';').withQuote('\'').parse("a;'b;c'\n") shouldBe Seq(IndexedSeq("a", "b;c"))
    CSV.noQuote().parse("a,\"b\n") shouldBe Seq(IndexedSeq("a", "\"b"))
  }

  test("read TSV with backslash escapes and \\N") {
    TSV.parse("name\tnote\nA\tx\\ty\\nz\\\\\nB\t\\N\n") shouldBe
      Seq(IndexedSeq("name", "note"), IndexedSeq("A", "x\ty\nz\\"), IndexedSeq("B", ""))
    TSV.read[CSVNote]("name\tnote\nA\t\nB\t\\N\nC\t\\\\N\n") shouldBe
      Seq(CSVNote("A", Some("")), CSVNote("B", None), CSVNote("C", Some("\\N")))
  }

  test("read quoted TSV") {
    TSV.quoted.parse("a\tb\n\"x\ty\"\t2\n") shouldBe
      Seq(IndexedSeq("a", "b"), IndexedSeq("x\ty", "2"))
  }

  test("reject a backslash at the end of TSV input") {
    val e = intercept[TextParseException] {
      TSV.parse("a\\")
    }
    e.getMessage shouldContain "backslash at the end"
  }

  test("write records with minimal quoting") {
    CSV.writeRecords(Seq(Seq("a", "b c", "x,y", "say \"hi\"", "line\nbreak", " pad", ""))) shouldBe
      "a,b c,\"x,y\",\"say \"\"hi\"\"\",\"line\nbreak\",\" pad\",\n"
  }

  test("write case classes with a header and read them back") {
    val people = Seq(
      CSVPerson("Alice", 30, Some("a,b")),
      CSVPerson("Bob", 25),
      CSVPerson("", 1, Some(""))
    )
    val text = CSV.write(people)
    text shouldBe "name,age,email\nAlice,30,\"a,b\"\nBob,25,\n\"\",1,\"\"\n"
    CSV.read[CSVPerson](text) shouldBe people
    CSV.noHeader().write(people.take(1)) shouldBe "Alice,30,\"a,b\"\n"
  }

  test("write nested values as JSON") {
    CSV.write(Seq(CSVNested(1, Seq("a", "b")))) shouldBe "id,tags\n1,\"[\"\"a\"\",\"\"b\"\"]\"\n"
  }

  test("write TSV with backslash escapes and read it back") {
    val rows = Seq(CSVRow("x\ty", Some("1\\2\nz")), CSVRow("\\N", None))
    val text = TSV.write(rows)
    text shouldBe "a\tb\nx\\ty\t1\\\\2\\nz\n\\\\N\t\\N\n"
    TSV.read[CSVRow](text) shouldBe rows
  }

  test("escape formulas when asked, but not numbers") {
    val f = CSV.withFormulaEscape()
    f.writeRecords(Seq(Seq("=SUM(A1)", "+1", "-5", "@x", "-", "1e3", "ok"))) shouldBe
      "'=SUM(A1),+1,-5,'@x,'-,1e3,ok\n"
    CSV.writeRecords(Seq(Seq("=SUM(A1)"))) shouldBe "=SUM(A1)\n"
  }

  test("refuse to write a field that plain mode can't hold") {
    intercept[IllegalArgumentException] {
      CSV.noQuote().writeRecords(Seq(Seq("a,b")))
    }
  }

  test("parse fields and quotes that span chunks") {
    val text     = "name,age,email\n\"Al\"\"ice\",30,\"a\r\nb\"\r\nBob,25,\n"
    val expected = CSV.read[CSVPerson](text)
    // Every split point, including inside "" and \r\n
    (1 until text.length).foreach { i =>
      val r = CSV.reader[CSVPerson](Iterator(text.substring(0, i), "", text.substring(i)))
      r.toSeq shouldBe expected
    }
    (1 until text.length).foreach { i =>
      CSV.recordReader(text.grouped(i)).toSeq shouldBe CSV.parse(text)
    }
  }

  test("decode UTF-8 characters split across byte chunks") {
    val text  = "name,age\n日本,1\n🎉é,2\n"
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    (1 to 5).foreach { size =>
      val chunks = bytes.grouped(size)
      CSV.reader[CSVPerson](CSVReader.decodeUTF8(chunks)).toSeq shouldBe
        Seq(CSVPerson("日本", 1), CSVPerson("🎉é", 2))
    }
  }

  test("close the source at the end, on an error, and on close") {
    class Source(chunks: String*) extends Iterator[String] with AutoCloseable:
      private val it      = chunks.iterator
      var closed: Boolean = false
      def hasNext         = it.hasNext
      def next()          = it.next()
      def close(): Unit   = closed = true

    val done = Source("a,b\n", "1,2\n")
    CSV.recordReader(done).toSeq
    done.closed shouldBe true

    val broken = Source("a,b\n", "1,\"x\n")
    intercept[TextParseException] {
      CSV.recordReader(broken).toSeq
    }
    broken.closed shouldBe true

    val early = Source("a,b\n", "1,2\n")
    val r     = CSV.recordReader(early)
    r.next()
    r.close()
    early.closed shouldBe true
    r.hasNext shouldBe false
  }

end CSVTest
