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
package wvlet.uni.cli.term

import wvlet.uni.rx.Cancelable

/**
  * A program running in a pseudo-terminal (PTY): what a [[TerminalEmulator]] talks to.
  *
  * Starting a PTY needs operating-system support that is not pure Scala, so backends live with the
  * platform: `PosixPty` on the JVM (Java 22+) and Scala Native, for Linux and macOS, and on Node.js
  * and Electron, `NodePty`, which wraps the `node-pty` package.
  */
trait PtyProcess:
  /**
    * Send input (keys, paste, terminal replies) to the program
    */
  def write(data: String): Unit

  /**
    * Tell the program the terminal size changed
    */
  def resize(cols: Int, rows: Int): Unit

  /**
    * Register a callback for program output
    */
  def onData(listener: String => Unit): Unit

  /**
    * Register a callback for the program's exit code
    */
  def onExit(listener: Int => Unit): Unit

  /**
    * Stop the program
    */
  def kill(): Unit

end PtyProcess

object PtyProcess:
  /**
    * Connect a terminal and a program both ways: program output goes to the terminal, and the
    * terminal's replies and user input go to the program. Cancelling stops sending terminal input
    * to the program.
    */
  def connect(terminal: TerminalEmulator, process: PtyProcess): Cancelable =
    // Replies first: output the process already buffered reaches the terminal at once, and the
    // terminal's answers to queries in it (DSR, DA) must reach the process
    val input = terminal.onData(process.write)
    process.onData(terminal.write)
    input
