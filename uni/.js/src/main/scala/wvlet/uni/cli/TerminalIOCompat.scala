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

import wvlet.uni.rx.Cancelable
import wvlet.uni.rx.Rx

import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

private[cli] object TerminalIOCompat:
  lazy val default: TerminalIO = NodeTerminalIO()

/**
  * The process's terminal on Node.js, through `process.stdin` and `process.stdout`. In a browser,
  * there is no terminal: [[isTTY]] is false and [[readInput]] fails.
  */
private[cli] class NodeTerminalIO extends TerminalIO:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val isNode: Boolean =
    js.typeOf(g.process) != "undefined" && js.typeOf(g.process.versions) != "undefined" &&
      js.typeOf(g.process.versions.node) != "undefined"

  private def stdin: js.Dynamic  = g.process.stdin
  private def stdout: js.Dynamic = g.process.stdout

  override val isTTY: Boolean =
    isNode && stdin.isTTY.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false) &&
      stdout.isTTY.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)

  private var rawMode    = false
  private var inputEnded = false

  override def size: TerminalSize =
    if isTTY then
      val cols = stdout.columns.asInstanceOf[js.UndefOr[Int]].getOrElse(0)
      val rows = stdout.rows.asInstanceOf[js.UndefOr[Int]].getOrElse(0)
      if cols > 0 && rows > 0 then
        TerminalSize(cols, rows)
      else
        TerminalSize.default
    else
      TerminalSize(CliEnv.terminalWidth, CliEnv.terminalHeight)

  override def enterRawMode(): Unit =
    if isTTY && !rawMode then
      stdin.setRawMode(true)
      rawMode = true

  override def exitRawMode(): Unit =
    if rawMode then
      stdin.setRawMode(false)
      rawMode = false

  override def write(s: String): Unit =
    if isNode then
      stdout.write(s)
    else
      System.out.print(s)

  override def flush(): Unit = ()

  override def readInput(handler: Array[Byte] => Boolean): Rx[Unit] =
    if !isNode then
      Rx.exception(UnsupportedOperationException("Terminal input requires Node.js"))
    else if inputEnded then
      Rx.single(handler(Array.emptyByteArray)).map(_ => ())
    else
      val done                               = Promise[Unit]()
      var onData: js.Function1[js.Any, Unit] = null
      var onEnd: js.Function0[Unit]          = null
      def stop(result: Try[Unit]): Unit      =
        stdin.removeListener("data", onData)
        stdin.removeListener("end", onEnd)
        stdin.pause()
        done.tryComplete(result)
      onData =
        (chunk: js.Any) =>
          Try(handler(toBytes(chunk))) match
            case Success(true)  =>
            case Success(false) =>
              stop(Success(()))
            case Failure(e) =>
              stop(Failure(e))
      onEnd =
        () =>
          inputEnded = true
          stop(Try(handler(Array.emptyByteArray)).map(_ => ()))
      stdin.on("data", onData)
      stdin.on("end", onEnd)
      stdin.resume()
      Rx.future(done.future)

  override def onInterrupt(handler: () => Unit): Cancelable =
    if !isNode then
      Cancelable.empty
    else
      val listener: js.Function0[Unit] = () => handler()
      g.process.on("SIGINT", listener)
      Cancelable(() => g.process.removeListener("SIGINT", listener))

  private def toBytes(chunk: js.Any): Array[Byte] =
    if js.typeOf(chunk) == "string" then
      chunk.asInstanceOf[String].getBytes("UTF-8")
    else
      val u8 = chunk.asInstanceOf[Uint8Array]
      Int8Array(u8.buffer, u8.byteOffset, u8.length).toArray

end NodeTerminalIO
