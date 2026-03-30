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
package wvlet.uni.io

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
  * Cross-platform exception for non-zero process exit codes.
  */
class NonZeroExitCodeException(val exitCode: Int, val command: Seq[String], message: String)
    extends Exception(message)

/**
  * Result of executing a command with `IO.run` or `IO.call`.
  */
case class CommandResult(exitCode: Int, stdout: String, stderr: String):
  def isSuccess: Boolean = exitCode == 0

/**
  * Configuration for process execution.
  */
case class ProcessConfig(
    workingDirectory: Option[IOPath] = None,
    env: Map[String, String] = Map.empty,
    inheritIO: Boolean = false,
    redirectErrorToOutput: Boolean = false
):
  def withWorkingDirectory(dir: IOPath): ProcessConfig         = copy(workingDirectory = Some(dir))
  def withEnv(key: String, value: String): ProcessConfig       = copy(env = env + (key -> value))
  def withEnv(vars: Map[String, String]): ProcessConfig        = copy(env = env ++ vars)
  def withInheritIO(value: Boolean): ProcessConfig             = copy(inheritIO = value)
  def withRedirectErrorToOutput(value: Boolean): ProcessConfig = copy(redirectErrorToOutput = value)

object ProcessConfig:
  val default: ProcessConfig = ProcessConfig()

/**
  * A handle to a spawned process started with `IO.spawn`.
  */
trait Process:
  def stdin: OutputStream
  def stdout: InputStream
  def stderr: InputStream
  def isAlive: Boolean
  def waitFor(): Int
  def waitFor(timeout: Long, unit: TimeUnit): Boolean
  def exitValue(): Int
  def destroy(): Unit
  def destroyForcibly(): Unit

/**
  * Base trait for process execution operations.
  */
trait ProcessApi:

  /**
    * Execute a command and wait for completion. When called with a single string containing spaces,
    * it is tokenized by splitting on whitespace, respecting single and double quotes.
    *
    * Examples:
    *   - `IO.run("ls -la /tmp")` — tokenized to `Seq("ls", "-la", "/tmp")`
    *   - `IO.run("echo", "hello")` — used as-is: `Seq("echo", "hello")`
    *
    * Note: For executables with spaces in the path, use the Seq overload:
    * `IO.run(Seq("/path/to/my program"), config)`
    *
    * @return
    *   CommandResult with exit code, stdout, and stderr
    */
  def run(command: String, rest: String*): CommandResult

  /**
    * Execute a command with configuration and wait for completion.
    */
  def run(command: Seq[String], config: ProcessConfig): CommandResult

  /**
    * Execute a command and wait for completion. Throws NonZeroExitCodeException on non-zero exit.
    * When called with a single string containing spaces, it is tokenized automatically.
    */
  def call(command: String, rest: String*): CommandResult

  /**
    * Execute a command with configuration and wait for completion. Throws NonZeroExitCodeException
    * on non-zero exit.
    */
  def call(command: Seq[String], config: ProcessConfig): CommandResult

  /**
    * Start a process without waiting for completion. When called with a single string containing
    * spaces, it is tokenized automatically.
    *
    * @return
    *   a Process handle for interacting with the running process
    */
  def spawn(command: String, rest: String*): Process

  /**
    * Start a process with configuration without waiting for completion.
    */
  def spawn(command: Seq[String], config: ProcessConfig): Process

end ProcessApi

object ProcessApi:

  /**
    * Resolve a command and rest arguments into a Seq. When rest is empty and the command contains
    * whitespace, it is tokenized as a command line string.
    *
    * Note: If the command is a path with spaces (e.g., "/path/to/my program"), use the Seq overload
    * instead: `IO.run(Seq("/path/to/my program"), config)`
    */
  def resolveCommand(command: String, rest: Seq[String]): Seq[String] =
    if rest.isEmpty && command.exists(_.isWhitespace) then
      tokenize(command)
    else
      command +: rest.toSeq

  /**
    * Tokenize a command line string into arguments, respecting single and double quotes.
    *
    * Examples:
    *   - `"ls -la"` -> `Seq("ls", "-la")`
    *   - `"echo 'hello world'"` -> `Seq("echo", "hello world")`
    *   - `"git commit -m \"initial commit\""` -> `Seq("git", "commit", "-m", "initial commit")`
    *
    * @throws IllegalArgumentException
    *   if the command line has unclosed quotes
    */
  def tokenize(commandLine: String): Seq[String] =
    val tokens        = Seq.newBuilder[String]
    val current       = StringBuilder()
    var i             = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var inToken       = false

    while i < commandLine.length do
      val c = commandLine(i)
      if inSingleQuote then
        if c == '\'' then
          inSingleQuote = false
        else
          current.append(c)
      else if inDoubleQuote then
        if c == '"' then
          inDoubleQuote = false
        else if c == '\\' && i + 1 < commandLine.length then
          val next = commandLine(i + 1)
          next match
            case '"' | '\\' =>
              current.append(next)
            case 'n' =>
              current.append('\n')
            case 't' =>
              current.append('\t')
            case 'r' =>
              current.append('\r')
            case _ =>
              current.append(c)
          i += 1
        else
          current.append(c)
      else if c == '\'' then
        inSingleQuote = true
        inToken = true
      else if c == '"' then
        inDoubleQuote = true
        inToken = true
      else if c == '\\' && i + 1 < commandLine.length then
        current.append(commandLine(i + 1))
        i += 1
        inToken = true
      else if c.isWhitespace then
        if current.nonEmpty || inToken then
          tokens += current.result()
          current.clear()
          inToken = false
      else
        current.append(c)
        inToken = true
      end if
      i += 1
    end while

    if inSingleQuote then
      throw IllegalArgumentException(s"Unclosed single quote in command: ${commandLine}")
    if inDoubleQuote then
      throw IllegalArgumentException(s"Unclosed double quote in command: ${commandLine}")

    if current.nonEmpty || inToken then
      tokens += current.result()
    tokens.result()

  end tokenize

end ProcessApi

/**
  * Unified I/O facade. Currently provides subprocess execution; file operations will be added in
  * future releases.
  *
  * Platform implementations:
  *   - JVM: Uses java.lang.ProcessBuilder
  *   - Scala.js: Not supported (throws UnsupportedOperationException)
  *   - Scala Native: Uses java.lang.ProcessBuilder
  */
object IO extends IOCompat
