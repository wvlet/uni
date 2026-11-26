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
package wvlet.uni.fuzz

import java.time.Instant
import wvlet.uni.test.UniTest
import wvlet.uni.test.fuzz.FuzzConfig
import wvlet.uni.text.cron.Cron
import wvlet.uni.text.csv.CSV
import wvlet.uni.text.csv.CSVFormat
import wvlet.uni.text.csv.TSV
import wvlet.uni.text.markdown.Markdown
import wvlet.uni.text.parser.TextParseException
import wvlet.uni.text.yaml.YAML

/**
  * Fuzz Uni's text formats. Invalid input must fail with [[TextParseException]], and what a format
  * reads it must write back so that reading it again gives the same value.
  */
class TextFormatFuzzTest extends UniTest:

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(2000)
    .withCorpusDir("uni/src/test/resources/fuzz")
    .withExpectedCases { case _: TextParseException =>
    }

  test("YAML parses or rejects input, and written documents read back the same") {
    fuzzString(
      "yaml-parse",
      fuzzConfig
        .withSeeds(
          "a: 1\nb: [x, y]\nc:\n  - {k: v}\n  - null\n",
          "---\nname: &n uni\nalias: *n\n---\n- 1.5\n- true\n- ~\n",
          "text: |\n  line 1\n  line 2\nfolded: >-\n  a\n  b\n",
          "'single': \"double \\u00e9\"\n? complex\n: value\n# comment\n",
          "%YAML 1.2\n--- !!map\n!!str k: !!int 3\n...\n"
        )
        .withDictionary(
          ": ",
          "- ",
          "---",
          "...",
          "|",
          ">",
          "&a ",
          "*a",
          "!!str ",
          "? ",
          "{",
          "}",
          "[",
          "]",
          "\"",
          "'",
          "#",
          "\n  "
        )
    ) { s =>
      val docs = YAML.parseDocuments(s)
      val text = YAML.writeStream(docs)
      YAML.parseAll(text) shouldBe YAML.parseAll(s)
    }
  }

  private def csvRoundTrip(target: String, format: CSVFormat, seeds: String*): Unit =
    fuzzString(
      target,
      fuzzConfig
        .withSeeds(seeds*)
        .withDictionary(",", "\t", "\"", "\"\"", "\\", "\\N", "\n", "\r\n")
    ) { s =>
      val records = format.parse(s)
      format.parse(format.writeRecords(records)) shouldBe records
    }

  test("CSV parses or rejects input, and written records read back the same") {
    csvRoundTrip(
      "csv-parse",
      CSV,
      "name,age\nAlice,30\nBob,25\n",
      "a,\"b,c\",\"d\"\"e\"\r\n,,\n\"multi\nline\",x\n"
    )
  }

  test("TSV parses or rejects input, and written records read back the same") {
    csvRoundTrip("tsv-parse", TSV, "name\tage\nAlice\t30\n", "a\\tb\t\\N\tc\\\\d\n\t\n")
  }

  test("cron parses or rejects input, and the next time it fires matches the schedule") {
    val after = Instant.ofEpochSecond(1_700_000_000L)
    fuzzString(
      "cron-parse",
      fuzzConfig
        .withSeeds(
          "*/5 * * * *",
          "0 9 * * 1-5",
          "30 0 1,15 * *",
          "0 0 29 2 *",
          "15 10 L * ?",
          "0 0 12 ? * MON#2",
          "@daily",
          "0 30 9 * JAN-MAR SUN"
        )
        .withDictionary(" ", "*", "/", "-", ",", "?", "L", "W", "#", "@", "MON", "JAN")
        .withExpectedCases {
          // A valid schedule that names a date that never comes, such as February 30
          case e: IllegalStateException if e.getMessage.contains("never fires") =>
        }
    ) { s =>
      val cron = Cron.parse(s)
      cron.describe
      val t = cron.next(after)
      t.isAfter(after) shouldBe true
      cron.matches(t) shouldBe true
    }
  }

  test("Markdown parses any input, and its written form is stable") {
    fuzzString(
      "markdown-parse",
      fuzzConfig
        .withSeeds(
          "# Title\n\nSome *em* and **strong** `code` [link](http://x \"t\").\n",
          "- a\n- b\n  1. c\n  2. d\n\n> quote\n> > nested\n",
          "```scala\nval x = 1\n```\n\n    indented\n\n---\n",
          "| a | b |\n|:--|--:|\n| 1 | 2 |\n\n- [ ] task\n- [x] done\n~~del~~ https://x.y\n",
          "[ref]: /url 'title'\n\n[ref] ![img](a.png) <b>html</b> &amp; \\* <http://a>\n",
          "---\ntitle: t\n---\nSetext\n======\n"
        )
        .withDictionary(
          "*",
          "_",
          "`",
          "```",
          "#",
          ">",
          "- ",
          "1. ",
          "[",
          "](",
          ")",
          "!",
          "<",
          "|",
          "~~",
          "\\",
          "&",
          "\n",
          "    "
        )
    ) { s =>
      val doc = Markdown.parse(s)
      Markdown.toHtml(s)
      val md = doc.toMarkdown
      Markdown.parse(md).toMarkdown shouldBe md
    }
  }

end TextFormatFuzzTest
