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

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private class JvmProcess(process: java.lang.Process) extends Process:
  override def stdin: OutputStream     = process.getOutputStream
  override def stdout: InputStream     = process.getInputStream
  override def stderr: InputStream     = process.getErrorStream
  override def isAlive: Boolean        = process.isAlive
  override def waitFor(): Int          = process.waitFor()
  override def destroy(): Unit         = process.destroy()
  override def destroyForcibly(): Unit =
    process.destroyForcibly();
    ()

/**
  * JVM implementation of process execution using java.lang.ProcessBuilder.
  */
trait IOCompat extends ProcessApi:

  override def run(command: String*): CommandResult = run(command.toSeq, ProcessConfig.default)

  override def run(command: Seq[String], config: ProcessConfig): CommandResult =
    val pb      = buildProcess(command, config)
    val process = pb.start()

    // Close stdin so commands that wait for EOF can terminate
    process.getOutputStream.close()

    // Read stdout and stderr in parallel to avoid deadlock when buffers fill
    var stdoutStr = ""
    var stderrStr = ""

    val stdoutThread = Thread(() => stdoutStr = readStream(process.getInputStream))
    val stderrThread = Thread(() => stderrStr = readStream(process.getErrorStream))
    stdoutThread.start()
    stderrThread.start()

    stdoutThread.join()
    stderrThread.join()
    val exitCode = process.waitFor()

    CommandResult(exitCode = exitCode, stdout = stdoutStr, stderr = stderrStr)

  override def call(command: String*): CommandResult = call(command.toSeq, ProcessConfig.default)

  override def call(command: Seq[String], config: ProcessConfig): CommandResult =
    val result = run(command, config)
    if !result.isSuccess then
      throw NonZeroExitCodeException(
        result.exitCode,
        command,
        s"Command failed with exit code ${result.exitCode}: ${command.mkString(
            " "
          )}\nstderr: ${result.stderr}"
      )
    result

  override def spawn(command: String*): Process = spawn(command.toSeq, ProcessConfig.default)

  override def spawn(command: Seq[String], config: ProcessConfig): Process =
    val pb      = buildProcess(command, config)
    val process = pb.start()
    JvmProcess(process)

  private def buildProcess(command: Seq[String], config: ProcessConfig): java.lang.ProcessBuilder =
    val pb = java.lang.ProcessBuilder(command*)
    config.workingDirectory.foreach(dir => pb.directory(File(dir.path)))
    if config.env.nonEmpty then
      val env = pb.environment()
      config.env.foreach((k, v) => env.put(k, v))
    if config.inheritIO then
      pb.inheritIO()
    if config.redirectErrorToOutput then
      pb.redirectErrorStream(true)
    pb

  private def readStream(is: InputStream): String = String(
    is.readAllBytes(),
    StandardCharsets.UTF_8
  )

end IOCompat
