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
package wvlet.uni.cli.launcher

import wvlet.uni.test.UniTest

class CommandLineTokenizerTest extends UniTest:

  test("tokenize simple arguments") {
    val tokens = CommandLineTokenizer.tokenize("arg1 arg2 arg3")
    tokens shouldBe Array("arg1", "arg2", "arg3")
  }

  test("tokenize with options") {
    val tokens = CommandLineTokenizer.tokenize("--verbose -n 10 file.txt")
    tokens shouldBe Array("--verbose", "-n", "10", "file.txt")
  }

  test("tokenize with double quotes") {
    val tokens = CommandLineTokenizer.tokenize("""--message "hello world" file.txt""")
    tokens shouldBe Array("--message", "hello world", "file.txt")
  }

  test("keep an unknown escape inside double quotes as written") {
    CommandLineTokenizer.tokenize(""""a\qb" "C:\dir"""") shouldBe Array("a\\qb", "C:\\dir")
    // As in a POSIX shell, \$ and \` drop the backslash
    CommandLineTokenizer.tokenize(""""\$HOME \`x\`"""") shouldBe Array("$HOME `x`")
  }

  test("tokenize with single quotes") {
    val tokens = CommandLineTokenizer.tokenize("--message 'hello world' file.txt")
    tokens shouldBe Array("--message", "hello world", "file.txt")
  }

  test("tokenize with escaped spaces") {
    val tokens = CommandLineTokenizer.tokenize("""--path /path/to/my\ file.txt""")
    tokens shouldBe Array("--path", "/path/to/my file.txt")
  }

  test("tokenize with mixed quotes") {
    val tokens = CommandLineTokenizer.tokenize("""'single "quotes"' "double 'quotes'" """)
    tokens shouldBe Array("""single "quotes"""", """double 'quotes'""")
  }

  test("tokenize empty string") {
    val tokens = CommandLineTokenizer.tokenize("")
    tokens shouldBe Array.empty[String]
  }

  test("tokenize null returns empty") {
    val tokens = CommandLineTokenizer.tokenize(null)
    tokens shouldBe Array.empty[String]
  }

  test("tokenize whitespace only") {
    val tokens = CommandLineTokenizer.tokenize("   \t\n  ")
    tokens shouldBe Array.empty[String]
  }

  test("tokenize with escape sequences in double quotes") {
    val tokens = CommandLineTokenizer.tokenize(""""hello\nworld" "tab\there"""")
    tokens shouldBe Array("hello\nworld", "tab\there")
  }

  test("reject unclosed single quote") {
    intercept[IllegalArgumentException] {
      CommandLineTokenizer.tokenize("'unclosed")
    }
  }

  test("reject unclosed double quote") {
    intercept[IllegalArgumentException] {
      CommandLineTokenizer.tokenize("\"unclosed")
    }
  }

end CommandLineTokenizerTest
