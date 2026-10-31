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
package example.repl

import wvlet.uni.cli.*
import wvlet.uni.design.Design
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest

class MiniReplTest extends UniTest:
  private def keys(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("run a multi-line statement on every platform") {
    val io = TerminalIO.inMemory(Seq(keys("select 1\r"), keys("from t;\r"), keys("exit\r")))
    // Same wiring as main, with an in-memory terminal and no history file
    val design =
      MiniRepl.design +
        Design
          .newDesign
          .bindInstance[TerminalIO](io)
          .bindInstance[LineReaderConfig](MiniRepl.config.noHistoryFile())

    design
      .run[MiniRepl, Rx[Unit]](_.run())
      .map { _ =>
        DisplayWidth.stripAnsi(io.output) shouldContain "ran: select 1 from t;"
        io.isRawMode shouldBe false
      }
  }

end MiniReplTest
