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

import scala.scalajs.js

/**
  * Scala.js process execution. On Node.js, `run` and `call` use `child_process.spawnSync`, loaded
  * at call time so browser bundles never reference it. With `redirectErrorToOutput`, stderr follows
  * stdout rather than interleaving with it. Browsers have no processes, and `spawn` (an interactive
  * process handle) is not supported on Scala.js.
  */
trait IOCompat extends ProcessApi:

  private def unsupported(what: String): Nothing =
    throw UnsupportedOperationException(s"${what} is not supported in this Scala.js environment")

  override def run(command: String, rest: String*): CommandResult = run(
    ProcessApi.resolveCommand(command, rest),
    ProcessConfig.default
  )

  override def run(command: Seq[String], config: ProcessConfig): CommandResult =
    if !NodeChildProcess.isNode then
      unsupported("Subprocess execution")
    if command.isEmpty then
      throw IllegalArgumentException("No command to run")
    val env  = js.Dictionary[js.Any]()
    val penv = js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[js.Any]]
    penv.foreach((k, v) => env(k) = v)
    config.env.foreach((k, v) => env(k) = v)
    val options = js
      .Dynamic
      .literal(
        env = env,
        encoding = "utf8",
        // Large enough for any command output a caller would capture as a String
        maxBuffer = 64 * 1024 * 1024,
        stdio = (
          if config.inheritIO then
            "inherit"
          else
            "pipe"
        )
      )
    config.workingDirectory.foreach(dir => options.updateDynamic("cwd")(dir.path))
    val result =
      NodeChildProcess
        .module
        .applyDynamic("spawnSync")(command.head, js.Array(command.tail*), options)
    if !js.isUndefined(result.error) && result.error != null then
      throw java
        .io
        .IOException(s"Failed to run ${command.head}: ${result.error.message.asInstanceOf[String]}")
    def text(v: js.Dynamic): String =
      if js.isUndefined(v) || v == null then
        ""
      else
        v.toString
    val exitCode =
      if js.isUndefined(result.status) || result.status == null then
        // Killed by a signal; Node reports no exit status then
        -1
      else
        result.status.asInstanceOf[Int]
    if config.redirectErrorToOutput then
      // spawnSync can't share one pipe between stdout and stderr, so stderr follows stdout
      CommandResult(exitCode, s"${text(result.stdout)}${text(result.stderr)}", "")
    else
      CommandResult(exitCode, text(result.stdout), text(result.stderr))

  end run

  override def call(command: String, rest: String*): CommandResult = call(
    ProcessApi.resolveCommand(command, rest),
    ProcessConfig.default
  )

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

  override def spawn(command: String, rest: String*): Process              = unsupported("IO.spawn")
  override def spawn(command: Seq[String], config: ProcessConfig): Process = unsupported("IO.spawn")

end IOCompat

/** Loads Node's `child_process` at run time, so a browser bundle never imports it */
private[io] object NodeChildProcess:
  def isNode: Boolean =
    // typeof, because reading an undeclared `process` global throws a ReferenceError in browsers
    js.typeOf(js.Dynamic.global.process) != "undefined" &&
      !js.isUndefined(js.Dynamic.global.process.versions) &&
      !js.isUndefined(js.Dynamic.global.process.versions.node)

  lazy val module: js.Dynamic =
    val process = js.Dynamic.global.process
    if !js.isUndefined(process.getBuiltinModule) then
      process.applyDynamic("getBuiltinModule")("child_process")
    else
      js.Dynamic.global.applyDynamic("require")("child_process")
