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

import wvlet.uni.cli.NativeTerminal.Backend
import wvlet.uni.test.UniTest

import java.io.File

class NativeTerminalTest extends UniTest:
  test("prefer FFM on Java 22+ and fall back to stty") {
    NativeTerminal.backends(22, isWindows = false) shouldBe List(Backend.Ffm, Backend.Stty)
    NativeTerminal.backends(25, isWindows = false) shouldBe List(Backend.Ffm, Backend.Stty)
  }

  test("use stty before Java 22") {
    NativeTerminal.backends(17, isWindows = false) shouldBe List(Backend.Stty)
    NativeTerminal.backends(21, isWindows = false) shouldBe List(Backend.Stty)
  }

  test("use line mode on Windows") {
    NativeTerminal.backends(17, isWindows = true) shouldBe Nil
    NativeTerminal.backends(25, isWindows = true) shouldBe Nil
  }

  test("parse stty size output") {
    SttyTerminal.parseSize("24 80") shouldBe Some(TerminalSize(80, 24))
    SttyTerminal.parseSize("  50   120\n") shouldBe Some(TerminalSize(120, 50))
    SttyTerminal.parseSize("0 0") shouldBe None
    SttyTerminal.parseSize("") shouldBe None
    SttyTerminal.parseSize("24") shouldBe None
    SttyTerminal.parseSize("rows cols") shouldBe None
  }

  test("report no terminal when the tty cannot be read") {
    val t = SttyTerminal(File("/nonexistent/tty"))
    t.isTTY shouldBe false
    t.enterRawMode() shouldBe false
    t.windowSize shouldBe None
  }

end NativeTerminalTest
