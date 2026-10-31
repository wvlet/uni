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
import wvlet.uni.rx.Rx

/**
  * A small SQL-like REPL built on LineReader. The same code runs on the JVM, as a Scala Native
  * binary, and on Node.js.
  */
object MiniRepl:
  private val keywords = Seq("select", "from", "where", "group", "by", "order", "limit")
  private val commands = Seq("help", "exit")

  private val keywordPattern = s"\\b(${keywords.mkString("|")})\\b".r

  private val help =
    """Statements end with ';'. Enter on an unfinished statement starts a new line.
      |  Tab            complete keywords
      |  Up/Down        history (also Ctrl-P/Ctrl-N), Ctrl-R to search it
      |  Ctrl-J Ctrl-R  run the statement, adding ';' if missing
      |  Ctrl-J Ctrl-D  describe the input above the prompt
      |  Ctrl-C         discard the input; Ctrl-D on an empty line exits
      |Commands: help, exit""".stripMargin

  private val runStatement = LineAction.Custom(
    "run-statement",
    editor =>
      editor.moveCursor(editor.buffer.length)
      if !editor.buffer.trim.endsWith(";") then
        editor.insert(";")
      editor.accept()
  )

  private val describeInput = LineAction.Custom(
    "describe-input",
    editor =>
      val lines = editor.buffer.split("\n", -1).length
      editor.printAbove(
        Tint.gray(
          s"${editor.buffer.length} characters, ${lines} line(s), cursor at ${editor.cursor}"
        )
      )
  )

  val config: LineReaderConfig = LineReaderConfig()
    .withHistoryFile("target/.uni-repl-history")
    .withCompleter(Completer.words(keywords ++ commands))
    .withHighlighter(s => keywordPattern.replaceAllIn(s, m => Tint.cyan(m.matched)))
    .withInputComplete { s =>
      val t = s.trim
      t.isEmpty || t.endsWith(";") || commands.contains(t)
    }
    .withContinuationPrompt(Tint.gray("  │ "))
    .withKeyMap(KeyMap.emacs.unbind("^J"))
    .bind("^J^R", runStatement)
    .bind("^J^D", describeInput)

  def main(args: Array[String]): Unit =
    println(help)
    loop(LineReader(config)).run()

  private def loop(reader: LineReader): Rx[Unit] = reader
    .readLine(Tint.green("uni> "))
    .flatMap {
      case LineResult.Line(text) if text.trim == "exit" =>
        Rx.single(())
      case LineResult.Line(text) =>
        text.trim match
          case ""     =>
          case "help" =>
            println(help)
          case statement =>
            println(s"${Tint.bold("ran")}: ${statement.replace("\n", " ")}")
        loop(reader)
      case LineResult.Interrupted(_) =>
        loop(reader)
      case LineResult.EndOfInput =>
        Rx.single(())
    }

end MiniRepl
