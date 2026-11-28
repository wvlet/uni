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
package wvlet.uni.http

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
  * A Lean model in `spec/lean` run as a fuzz oracle: one process, sent one hex-encoded input per
  * line, answering one line per input with hex-encoded fields separated by spaces (see
  * `spec/lean/HttpReaderOracle.lean`). Lean stays a dev-only tool (`adr/2026-11-18-lean-specs.md`),
  * so a test asks [[LeanOracle.find]] for one and skips the model check when it gets none.
  */
class LeanOracle(command: Seq[String]) extends AutoCloseable:
  private val process = ProcessBuilder(command*)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

  private val in  = OutputStreamWriter(process.getOutputStream, StandardCharsets.US_ASCII)
  private val out = BufferedReader(
    InputStreamReader(process.getInputStream, StandardCharsets.US_ASCII)
  )

  /** The model's answer for `input`, as the fields of its reply line. */
  def ask(input: Array[Byte]): Seq[Array[Byte]] = synchronized {
    in.write(LeanOracle.hex(input))
    in.write('\n')
    in.flush()
    val line = out.readLine()
    if line == null then
      throw IllegalStateException(s"${command.head} exited with ${process.waitFor()}")
    if line.startsWith("error ") then
      throw IllegalArgumentException(s"${command.head}: ${line}")
    line.split(' ').toSeq.filter(_.nonEmpty).map(LeanOracle.unhex)
  }

  /** Close stdin, which ends the process; a process that already exited leaves a broken pipe */
  override def close(): Unit =
    try
      in.close()
    catch
      case _: java.io.IOException =>
    process.waitFor()

end LeanOracle

object LeanOracle:
  /**
    * The executable `exe` built in `spec/lean`: the path in the environment variable `envVar`, or
    * else Lake's default output under the repository root, where sbt runs tests. Left: the absolute
    * path looked at, when neither exists. A path given in `envVar` must exist, so a job that meant
    * to use the oracle fails without it instead of skipping the check.
    */
  def find(exe: String, envVar: String): Either[String, File] =
    Option(System.getenv(envVar)).filter(_.nonEmpty) match
      case Some(path) =>
        val f = File(path)
        if !f.canExecute then
          throw IllegalStateException(s"${envVar}=${path} is not an executable file")
        Right(f)
      case None =>
        val f = File(s"spec/lean/.lake/build/bin/${exe}").getAbsoluteFile
        if f.canExecute then
          Right(f)
        else
          Left(f.getPath)

  private val digits = "0123456789abcdef"

  def hex(bytes: Array[Byte]): String =
    val sb = StringBuilder(bytes.length * 2)
    bytes.foreach { b =>
      sb.append(digits((b >> 4) & 0xf)).append(digits(b & 0xf))
    }
    sb.result()

  def unhex(s: String): Array[Byte] =
    Array.tabulate(s.length / 2) { i =>
      Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16).toByte
    }

end LeanOracle
