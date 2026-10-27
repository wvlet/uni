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
package wvlet.uni.cli

import wvlet.uni.test.UniTest
import wvlet.uni.test.empty
import wvlet.uni.test.defined

class TintTest extends UniTest:
  private val RESET = "\u001b[0m"

  test("apply foreground color") {
    Tint.red("hello") shouldBe s"\u001b[31mhello${RESET}"
    Tint.green("hello") shouldBe s"\u001b[32mhello${RESET}"
    Tint.blue("hello") shouldBe s"\u001b[34mhello${RESET}"
    Tint.yellow("hello") shouldBe s"\u001b[33mhello${RESET}"
    Tint.cyan("hello") shouldBe s"\u001b[36mhello${RESET}"
    Tint.magenta("hello") shouldBe s"\u001b[35mhello${RESET}"
    Tint.white("hello") shouldBe s"\u001b[37mhello${RESET}"
    Tint.black("hello") shouldBe s"\u001b[30mhello${RESET}"
  }

  test("apply bright foreground colors") {
    Tint.brightRed("hello") shouldBe s"\u001b[91mhello${RESET}"
    Tint.brightGreen("hello") shouldBe s"\u001b[92mhello${RESET}"
    Tint.gray("hello") shouldBe s"\u001b[90mhello${RESET}"
  }

  test("apply text modifiers") {
    Tint.bold("hello") shouldBe s"\u001b[1mhello${RESET}"
    Tint.dim("hello") shouldBe s"\u001b[2mhello${RESET}"
    Tint.italic("hello") shouldBe s"\u001b[3mhello${RESET}"
    Tint.underline("hello") shouldBe s"\u001b[4mhello${RESET}"
    Tint.strikethrough("hello") shouldBe s"\u001b[9mhello${RESET}"
    Tint.inverse("hello") shouldBe s"\u001b[7mhello${RESET}"
  }

  test("chain foreground color and modifier") {
    val result = Tint.red.bold("hello")
    result shouldContain "\u001b[31m"
    result shouldContain "\u001b[1m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("chain multiple modifiers") {
    val result = Tint.red.bold.underline("hello")
    result shouldContain "\u001b[31m"
    result shouldContain "\u001b[1m"
    result shouldContain "\u001b[4m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("apply background color") {
    val result = Tint().bgRed("hello")
    result shouldContain "\u001b[41m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("combine foreground and background colors") {
    val result = Tint.white.bgBlue("hello")
    result shouldContain "\u001b[37m"
    result shouldContain "\u001b[44m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("disabled tint returns plain text") {
    Tint().noColor("hello") shouldBe "hello"
    Tint.red.noColor("hello") shouldBe "hello"
  }

  test("return plain text when no styling applied") {
    Tint()("hello") shouldBe "hello"
  }

  test("support RGB colors") {
    val result = Tint().rgb(255, 87, 51)("hello")
    result shouldContain "\u001b[38;2;255;87;51m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("support hex colors") {
    val result = Tint().hex("#FF5733")("hello")
    result shouldContain "\u001b[38;2;255;87;51m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("support 256 colors") {
    val result = Tint().ansi256(208)("hello")
    result shouldContain "\u001b[38;5;208m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("support background RGB colors") {
    val result = Tint().bgRgb(255, 87, 51)("hello")
    result shouldContain "\u001b[48;2;255;87;51m"
    result shouldContain "hello"
    result shouldContain RESET
  }

  test("companion object exposes extended and bright background colors") {
    Tint.bgRgb(100, 149, 237)("hello") shouldContain "\u001b[48;2;100;149;237m"
    Tint.bgHex("#663399")("hello") shouldContain "\u001b[48;2;102;51;153m"
    Tint.bgAnsi256(57)("hello") shouldContain "\u001b[48;5;57m"
    Tint.bgBrightRed("hello") shouldBe Tint().bgBrightRed("hello")
  }

  test("create reusable styles") {
    val errorStyle   = Tint.red.bold
    val successStyle = Tint.green

    errorStyle("Failed!") shouldContain "\u001b[31m"
    errorStyle("Failed!") shouldContain "\u001b[1m"
    successStyle("Passed!") shouldContain "\u001b[32m"
  }

  test("reset returns empty tint") {
    val styled = Tint.red.bold
    val reset  = styled.reset
    reset("hello") shouldBe "hello"
  }

end TintTest
