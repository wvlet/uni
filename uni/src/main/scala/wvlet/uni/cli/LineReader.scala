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

import wvlet.uni.io.IOPath
import wvlet.uni.rx.Rx

/**
  * The outcome of [[LineReader.readLine]]
  */
enum LineResult:
  /**
    * The accepted input. It can contain line breaks when the input spans several lines.
    */
  case Line(text: String)

  /**
    * Ctrl-C was pressed; `partial` is what had been typed
    */
  case Interrupted(partial: String)

  /**
    * Ctrl-D on an empty line, or the input was closed
    */
  case EndOfInput

/**
  * Configuration of a [[LineReader]]
  *
  * @param completer
  *   computes candidates for Tab
  * @param highlighter
  *   adds colors to the input for display, e.g., with [[Tint]]. It must keep the visible text and
  *   its line breaks unchanged.
  * @param isInputComplete
  *   called on Enter; when it returns false, Enter starts a new line in the same input. Use it for
  *   statements that end with `;` or unbalanced brackets.
  * @param continuationPrompt
  *   the prompt for the second and later lines of the input. When empty, those lines are indented
  *   to the width of the first prompt.
  * @param keyMap
  *   key bindings; defaults to Emacs-style bindings
  * @param historyFile
  *   a file to load history from and append each accepted input to
  * @param historySize
  *   the number of history entries to keep
  */
case class LineReaderConfig(
    completer: Completer = Completer.empty,
    highlighter: String => String = (s: String) => s,
    isInputComplete: String => Boolean = (_: String) => true,
    continuationPrompt: String = "",
    keyMap: KeyMap = KeyMap.emacs,
    historyFile: Option[String] = None,
    historySize: Int = 1000
):
  def withCompleter(c: Completer): LineReaderConfig             = copy(completer = c)
  def withHighlighter(f: String => String): LineReaderConfig    = copy(highlighter = f)
  def withInputComplete(f: String => Boolean): LineReaderConfig = copy(isInputComplete = f)
  def withContinuationPrompt(prompt: String): LineReaderConfig  = copy(continuationPrompt = prompt)
  def withKeyMap(k: KeyMap): LineReaderConfig                   = copy(keyMap = k)
  def withHistoryFile(path: String): LineReaderConfig           = copy(historyFile = Some(path))
  def noHistoryFile(): LineReaderConfig                         = copy(historyFile = None)
  def withHistorySize(size: Int): LineReaderConfig              = copy(historySize = size)
  def bind(sequence: String, action: LineAction): LineReaderConfig = copy(keyMap =
    keyMap.bind(sequence, action)
  )

end LineReaderConfig

/**
  * A line editor for interactive command-line programs such as REPLs, on the JVM, Scala Native, and
  * Node.js. It provides Emacs-style editing, history with reverse search (Ctrl-R), Tab completion,
  * syntax highlighting, multi-line input, and custom key bindings.
  *
  * {{{
  * val reader = LineReader(
  *   LineReaderConfig()
  *     .withHistoryFile(".my_history")
  *     .withCompleter(Completer.words(Seq("select", "from", "where")))
  *     .withInputComplete(_.trim.endsWith(";"))
  * )
  * // On the JVM and Scala Native
  * reader.readLine("> ").await match
  *   case LineResult.Line(text)      => ...
  *   case LineResult.Interrupted(_)  => ...
  *   case LineResult.EndOfInput      => ...
  * }}}
  *
  * When stdin is not a terminal (a pipe, a file, CI), input is read line by line without editing or
  * drawing, and multi-line checks are skipped.
  */
class LineReader(
    val config: LineReaderConfig = LineReaderConfig(),
    io: TerminalIO = TerminalIO.default
):
  import LineReader.*

  val history: LineHistory = LineHistory(config.historySize, config.historyFile.map(IOPath.parse))

  private val decoder = KeyDecoder(lineMode = !io.isTTY)
  // Keys decoded but not consumed yet, e.g., the lines after the first in pasted input
  private var queued     = Vector.empty[Key]
  private var inputEnded = false
  private var current    = Option.empty[LineEditor]

  def terminal: TerminalIO = io

  /**
    * Read one input. On the JVM and Scala Native, `.await` the result to block until the user
    * presses Enter; on Scala.js, the Rx completes asynchronously.
    */
  def readLine(prompt: String): Rx[LineResult] = Rx
    .single(begin(prompt))
    .flatMap { editor =>
      val input =
        if editor.isDone then
          Rx.single(())
        else
          io.readInput(bytes => onInput(editor, bytes))
      input.transform { r =>
        end()
        r.get
        editor.result.getOrElse(LineResult.EndOfInput)
      }
    }

  /**
    * Print a message above the input while [[readLine]] is waiting, e.g., from a background task.
    * Outside readLine, just prints the message.
    */
  def printAbove(message: String): Unit = synchronized {
    current match
      case Some(editor) =>
        editor.printAbove(message)
      case None =>
        io.write(
          if message.endsWith("\n") then
            message
          else
            s"${message}\n"
        )
        io.flush()
  }

  private def begin(prompt: String): LineEditor = synchronized {
    if current.isDefined then
      throw IllegalStateException("readLine is already in progress")
    val editor = LineEditor(prompt, config, history, io)
    current = Some(editor)
    if io.isTTY then
      io.enterRawMode()
      io.write(BracketedPasteOn)
      editor.render()
    drain(editor)
    if inputEnded then
      editor.endOfInput()
    editor
  }

  private def end(): Unit = synchronized {
    current = None
    if io.isTTY then
      io.write(BracketedPasteOff)
      io.flush()
      io.exitRawMode()
  }

  private def onInput(editor: LineEditor, bytes: Array[Byte]): Boolean = synchronized {
    if bytes.isEmpty then
      queued ++= decoder.flush()
      drain(editor)
      inputEnded = true
      editor.endOfInput()
    else
      queued ++= decoder.feed(bytes)
      drain(editor)
    !editor.isDone
  }

  private def drain(editor: LineEditor): Unit =
    val hadKeys = queued.nonEmpty
    while queued.nonEmpty && !editor.isDone do
      val key = queued.head
      queued = queued.tail
      editor.handle(key)
    if hadKeys && !editor.isDone then
      editor.render()

end LineReader

object LineReader:
  private val BracketedPasteOn  = "\u001b[?2004h"
  private val BracketedPasteOff = "\u001b[?2004l"
