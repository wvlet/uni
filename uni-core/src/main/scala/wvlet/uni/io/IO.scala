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
    * Execute a command and wait for completion.
    *
    * @return
    *   CommandResult with exit code, stdout, and stderr
    */
  def run(command: String*): CommandResult

  /**
    * Execute a command with configuration and wait for completion.
    */
  def run(command: Seq[String], config: ProcessConfig): CommandResult

  /**
    * Execute a command and wait for completion. Throws NonZeroExitCodeException on non-zero exit.
    */
  def call(command: String*): CommandResult

  /**
    * Execute a command with configuration and wait for completion. Throws NonZeroExitCodeException
    * on non-zero exit.
    */
  def call(command: Seq[String], config: ProcessConfig): CommandResult

  /**
    * Start a process without waiting for completion.
    *
    * @return
    *   a Process handle for interacting with the running process
    */
  def spawn(command: String*): Process

  /**
    * Start a process with configuration without waiting for completion.
    */
  def spawn(command: Seq[String], config: ProcessConfig): Process

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
