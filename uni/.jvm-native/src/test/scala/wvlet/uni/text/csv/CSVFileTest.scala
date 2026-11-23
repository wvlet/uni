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
import wvlet.uni.test.UniTest

class CSVFileTest extends UniTest:

  test("write a file in chunks and stream it back") {
    val dir = FileSystem.createTempDirectory("uni-csv")
    try
      val path   = dir / "people.csv"
      val people = (1 to 5000).map(i =>
        CSVPerson(s"名前 ${i}, \"q\"", i, Option.when(i % 2 == 0)("x"))
      )
      CSV.writeFile(path, people)
      val r = CSV.readFile[CSVPerson](path)
      try r.toSeq shouldBe people
      finally r.close()

      val records = CSV.readFileRecords(path)
      try records.next() shouldBe IndexedSeq("name", "age", "email")
      finally records.close()
    finally
      FileSystem.deleteRecursively(dir)
  }

  test("write records to a TSV file") {
    val dir = FileSystem.createTempDirectory("uni-csv")
    try
      val path = dir / "rows.tsv"
      TSV.writeRecordsFile(path, Seq(Seq("a", "b"), Seq("x\ty", "")))
      FileSystem.readString(path) shouldBe "a\tb\nx\\ty\t\n"
      TSV.parse(FileSystem.readString(path)) shouldBe
        Seq(IndexedSeq("a", "b"), IndexedSeq("x\ty", ""))
    finally
      FileSystem.deleteRecursively(dir)
  }

end CSVFileTest
