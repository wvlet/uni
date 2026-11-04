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

import scala.scalajs.js

/**
  * Options for starting a program with [[NodePty.spawn]]
  */
case class PtyOptions(
    cols: Int = 80,
    rows: Int = 24,
    cwd: Option[String] = None,
    env: Map[String, String] = Map.empty,
    termName: String = "xterm-256color"
):
  def withSize(cols: Int, rows: Int): PtyOptions    = copy(cols = cols, rows = rows)
  def withCwd(cwd: String): PtyOptions              = copy(cwd = Some(cwd))
  def noCwd(): PtyOptions                           = copy(cwd = None)
  def withEnv(env: Map[String, String]): PtyOptions = copy(env = env)
  def withTermName(termName: String): PtyOptions    = copy(termName = termName)

/**
  * Starts programs in a pseudo-terminal on Node.js and in Electron's main process, through the
  * [[https://github.com/microsoft/node-pty node-pty]] package.
  *
  * node-pty is a native addon, so uni does not depend on it. The app installs it and passes the
  * loaded module in:
  * {{{
  *   // main.mjs
  *   import * as pty from "node-pty"
  *   startTerminalService(pty)
  *
  *   // Scala.js
  *   @JSExportTopLevel("startTerminalService")
  *   def startTerminalService(pty: js.Dynamic): Unit =
  *     val shell = NodePty.spawn(pty, "/bin/zsh", Seq("-l"), PtyOptions().withSize(80, 24))
  *     shell.onData(data => ...)
  * }}}
  */
object NodePty:
  /**
    * Start `file` with `args` in a new pseudo-terminal. `nodePty` is the loaded node-pty module.
    * The environment is the current process's, plus `options.env` and `TERM`.
    */
  def spawn(
      nodePty: js.Dynamic,
      file: String,
      args: Seq[String] = Seq.empty,
      options: PtyOptions = PtyOptions()
  ): PtyProcess =
    val env        = js.Dictionary.empty[String]
    val processEnv = js.Dynamic.global.process.env.asInstanceOf[js.Dictionary[js.Any]]
    processEnv.foreach { case (k, v) =>
      if !js.isUndefined(v) && v != null then
        env(k) = v.toString
    }
    options.env.foreach((k, v) => env(k) = v)
    env("TERM") = options.termName
    val opts = js
      .Dynamic
      .literal(name = options.termName, cols = options.cols, rows = options.rows, env = env)
    options.cwd.foreach(dir => opts.updateDynamic("cwd")(dir))
    val handle = nodePty.spawn(file, js.Array(args*), opts)
    NodePtyProcess(handle)

  private class NodePtyProcess(handle: js.Dynamic) extends PtyProcess:
    override def write(data: String): Unit = handle.write(data)

    override def resize(cols: Int, rows: Int): Unit = handle.resize(
      math.max(1, cols),
      math.max(1, rows)
    )

    override def onData(listener: String => Unit): Unit = handle.onData((data: String) =>
      listener(data)
    )

    override def onExit(listener: Int => Unit): Unit = handle.onExit((e: js.Dynamic) =>
      val code = e.exitCode
      listener(
        if js.isUndefined(code) || code == null then
          -1
        else
          code.asInstanceOf[Int]
      )
    )

    override def kill(): Unit = handle.kill()

end NodePty
