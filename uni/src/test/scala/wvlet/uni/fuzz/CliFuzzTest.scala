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

import wvlet.uni.cli.launcher.CommandLineTokenizer
import wvlet.uni.cli.term.CsiParams
import wvlet.uni.cli.term.EscapeHandler
import wvlet.uni.cli.term.EscapeParser
import wvlet.uni.cloud.CloudException
import wvlet.uni.cloud.XmlReader
import wvlet.uni.test.UniTest
import wvlet.uni.test.check.Gen
import wvlet.uni.test.fuzz.FuzzConfig

import java.nio.charset.StandardCharsets

/**
  * Fuzz the readers of command lines, terminal output, and cloud storage XML responses: what they
  * can't read fails with their own error, and what they read agrees with how it was written.
  */
class CliFuzzTest extends UniTest:

  override protected def fuzzConfig: FuzzConfig = FuzzConfig()
    .withIterations(2000)
    .withCorpusDir("uni/src/test/resources/fuzz")

  private val tokenChar: Gen[Char] = Gen.frequency(
    6 -> Gen.alphaNumChar,
    3 -> Gen.oneOf(' ', '\t', '\n', '\'', '"', '\\', '-', '=', '/', '$', 'é'),
    1 -> Gen.asciiChar
  )

  // Each token written bare with backslash escapes, in single quotes, or in double quotes
  private def quote(token: String, style: Int): String =
    style match
      case 0 if token.nonEmpty =>
        token.flatMap(c =>
          if c.isWhitespace || "'\"\\".contains(c) then
            s"\\${c}"
          else
            c.toString
        )
      case 1 =>
        "'" + token.replace("'", "'\\''") + "'"
      case _ =>
        "\"" +
          token.flatMap(c =>
            if c == '"' || c == '\\' then
              s"\\${c}"
            else
              c.toString
          ) + "\""

  test("the command line tokenizer reads back quoted tokens") {
    val tokens = Gen.listOf(Gen.stringOf(tokenChar).zip(Gen.chooseNum(0, 2)))
    fuzzGen("command-line-tokenize", tokens) { ts =>
      val line = ts.map(quote).mkString(" ")
      CommandLineTokenizer.tokenize(line).toList shouldBe ts.map(_._1)
    }
  }

  test("the command line tokenizer rejects unclosed quotes with IllegalArgumentException") {
    fuzzString(
      "command-line-parse",
      fuzzConfig
        .withSeeds("""a 'b c' "d \"e\" \\ \q" f\ g""", "--opt=\"x y\" -n 1")
        .withDictionary("'", "\"", "\\", " ", "\\\"")
        .withExpectedCases { case _: IllegalArgumentException =>
        }
    ) { s =>
      CommandLineTokenizer.tokenize(s)
    }
  }

  test("the XML reader rejects malformed responses with CloudException") {
    fuzzString(
      "xml-parse",
      fuzzConfig
        .withSeeds(
          """<?xml version="1.0" encoding="UTF-8"?>
            |<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
            |<Name>b</Name><Contents><Key>a&amp;b&#x41;&#66;</Key><Size>3</Size></Contents>
            |<!-- c --><IsTruncated>false</IsTruncated></ListBucketResult>""".stripMargin,
          """<a k="v" q='x>y' e="&amp;&lt;"><b flag/><c n="1"/>text<![CDATA[<x>]]></a>""",
          "<!DOCTYPE a><a:b xmlns:a=\"u\"><a:c/></a:b>"
        )
        .withDictionary(
          "<",
          "</",
          ">",
          "/>",
          "=\"",
          "&amp;",
          "&#",
          "&#x",
          "<![CDATA[",
          "]]>",
          "<!--",
          "-->",
          "<?",
          "?>"
        )
        .withExpectedCases { case _: CloudException =>
        }
    ) { s =>
      XmlReader.parse(s)
    }
  }

  // Records what the parser decoded, to compare two ways of feeding it
  private class Recorder extends EscapeHandler:
    val out                                               = StringBuilder()
    def print(codePoint: Int): Unit                       = out.append(s"p${codePoint} ")
    def execute(code: Int): Unit                          = out.append(s"x${code} ")
    def esc(intermediates: String, finalChar: Char): Unit = out.append(
      s"esc[${intermediates}]${finalChar.toInt} "
    )

    def osc(data: String): Unit = out.append(s"osc[${data}] ")
    def csi(params: CsiParams, prefix: Char, intermediates: String, finalChar: Char): Unit =
      val ps = (0 until params.size).map(i =>
        s"${params(i, -1)}${
            if params.isSubParam(i) then
              ":"
            else
              ""
          }"
      )
      out.append(s"csi${prefix.toInt}[${ps.mkString(",")}][${intermediates}]${finalChar.toInt} ")

  test("the escape parser decodes UTF-8 bytes as it decodes the same text") {
    fuzz(
      "terminal-escape-parse",
      fuzzConfig
        .withSeeds(
          "plain é 😀\r\n\u0007",
          "\u001b[1;31mred\u001b[0m\u001b[?25l\u001b[38:2::255:0:0m",
          "\u001b]0;title\u0007\u001b]8;;https://a\u001b\\link\u001b]8;;\u001b\\",
          "\u001b(0q\u001b(B\u001b7\u001b8\u001bP1$r\u001b\\"
        )
        .withDictionary("\u001b[", "\u001b]", "\u001b\\", "\u001bP", ";", ":", "?", "\u0007", "m")
    ) { bytes =>
      val text = String(bytes, StandardCharsets.UTF_8)
      // Only input that is valid UTF-8 has a text form to compare with
      if java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes) then
        val fromBytes = Recorder()
        EscapeParser(fromBytes).feed(bytes)
        val fromText = Recorder()
        EscapeParser(fromText).feed(text)
        fromBytes.out.toString shouldBe fromText.out.toString
      else
        EscapeParser(Recorder()).feed(bytes)
    }
  }

end CliFuzzTest
