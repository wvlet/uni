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

/**
  * A command the shell ran, found from the shell integration marks (OSC 133) in the terminal's
  * history. The shell must emit the marks; see the uni-harness example for zsh and bash scripts.
  *
  * @param line
  *   the history index of the prompt line (see [[Screen.historyLine]])
  * @param command
  *   the command line the user entered
  * @param output
  *   what the command printed, without trailing empty lines
  * @param exitCode
  *   the exit code, once the command finished
  */
case class TerminalCommand(line: Int, command: String, output: String, exitCode: Option[Int]):
  def isFinished: Boolean = exitCode.isDefined
  def isFailed: Boolean   = exitCode.exists(_ != 0)

object TerminalCommand:

  /**
    * The commands in a screen's history, oldest first. A prompt with no output mark (an empty
    * command line, or a shell without integration) is not a command.
    */
  def scan(screen: Screen): Seq[TerminalCommand] =
    val n     = screen.historySize
    val found = Seq.newBuilder[TerminalCommand]
    var i     = 0
    while i < n do
      if screen.historyLine(i).isPromptStart then
        at(screen, i).foreach(found += _)
      i += 1
    found.result()

  /**
    * The command whose prompt is on history line `promptLine`, if its output started
    */
  def at(screen: Screen, promptLine: Int): Option[TerminalCommand] =
    val n = screen.historySize
    // The command line runs from the input mark to the output mark, and the output to the next
    // prompt. A command that printed nothing leaves its output mark on the next prompt's line, so
    // an output mark on the prompt line itself belongs to the command before.
    var outputStart = -1
    var end         = n
    var i           = promptLine + 1
    while i < n && end == n do
      val line = screen.historyLine(i)
      if outputStart < 0 && line.isOutputStart then
        outputStart = i
      if line.isPromptStart then
        end = i
      i += 1
    if outputStart < 0 then
      None
    else
      val command = StringBuilder()
      var j       = promptLine
      var inInput = false
      while j < outputStart do
        val line = screen.historyLine(j)
        if (line.marks & PromptMarks.Input) != 0 && line.inputCol >= 0 then
          inInput = true
          command.append(line.textFrom(line.inputCol))
        else if inInput then
          if !line.isWrapped then
            command.append('\n')
          command.append(line.text)
        j += 1
      val output = StringBuilder()
      var k      = outputStart
      while k < end do
        val line = screen.historyLine(k)
        if k > outputStart && !line.isWrapped then
          output.append('\n')
        output.append(line.text)
        k += 1
      val prompt   = screen.historyLine(promptLine)
      val reported = screen.historyLine(outputStart).cmdline
      Some(
        TerminalCommand(
          promptLine,
          if reported != null then
            reported
          else
            command.toString.trim
          ,
          output.toString.replaceAll("\\s+$", ""),
          prompt.exitCode
        )
      )
    end if

  end at

  /**
    * Decode `%XX` escapes as UTF-8 bytes, leaving other characters as they are
    */
  private[term] def percentDecode(s: String): String =
    if !s.contains('%') then
      s
    else
      val out                = StringBuilder()
      val bytes              = java.io.ByteArrayOutputStream()
      def flushBytes(): Unit =
        if bytes.size() > 0 then
          out.append(String(bytes.toByteArray, java.nio.charset.StandardCharsets.UTF_8))
          bytes.reset()
      var i = 0
      while i < s.length do
        val c = s.charAt(i)
        if c == '%' && i + 2 < s.length && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2)) then
          bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16))
          i += 3
        else
          flushBytes()
          out.append(c)
          i += 1
      flushBytes()
      out.toString

  private def isHex(c: Char): Boolean = Character.digit(c, 16) >= 0

end TerminalCommand
