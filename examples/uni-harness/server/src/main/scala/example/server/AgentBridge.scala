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
package example.server

import wvlet.uni.agent.UiAgentApi
import wvlet.uni.electron.{ElectronMain, ElectronMainChannelConfig}
import wvlet.uni.http.{
  Http,
  HttpAsyncClient,
  HttpHeader,
  HttpServer,
  HttpStatus,
  Request,
  Response,
  RxHttpHandler
}
import wvlet.uni.http.rpc.{RPCClient, RPCStatus}
import wvlet.uni.mcp.MCPServer
import wvlet.uni.rx.Rx

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Uint8Array

/**
  * The agent bridge: an MCP endpoint in the Electron main process that lets an AI agent (Claude
  * Code, any MCP client) read and operate the renderer's UI through its `UiAgent`.
  *
  * It runs only when the app starts with `--agent-bridge` (`pnpm dev:agent`). It listens on
  * 127.0.0.1 at a port the OS picks, and every request must carry `Authorization: Bearer <token>`,
  * a random token made at startup. `src/main/index.js` prints the URL and the token.
  *
  * Each MCP tool call goes to the renderer over the main→renderer IPC channel, where the renderer
  * serves `UiAgentApi` with `ElectronRenderer.serve`.
  */
object AgentBridge:

  /** The path the MCP endpoint answers on. */
  val Path: String = "/mcp"

  /** A random token: 32 bytes from the platform's secure random source, as hex. */
  def newToken(): String =
    val bytes = Uint8Array(32)
    js.Dynamic.global.crypto.getRandomValues(bytes)
    bytes.map(b => f"${b.toInt}%02x").mkString

  /**
    * Whether the request carries `Authorization: Bearer <token>`. The scheme is case-insensitive
    * (RFC 7235); the token is compared in constant time.
    */
  def isAuthorized(request: Request, token: String): Boolean = request
    .header(HttpHeader.Authorization)
    .exists { value =>
      val scheme = "bearer "
      value.length == scheme.length + token.length &&
      value.take(scheme.length).toLowerCase == scheme && {
        val presented = value.drop(scheme.length)
        presented.zip(token).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
      }
    }

  /** The MCP endpoint for `api`, behind the bearer token. */
  def handler(api: UiAgentApi, token: String): RxHttpHandler =
    val mcp =
      MCPServer()
        .withName("uni-harness")
        .withVersion("0.1.0")
        .withTools[UiAgentApi](api)
        .httpHandler
    RxHttpHandler { request =>
      if request.path != Path then
        Rx.single(Response.notFound)
      else if !isAuthorized(request, token) then
        Rx.single(
          Response(HttpStatus.Unauthorized_401).addHeader(HttpHeader.WWWAuthenticate, "Bearer")
        )
      else
        mcp.handle(request)
    }

  /** `UiAgentApi` calls forwarded to the renderer through an RPC client. */
  class Forwarder(client: () => Option[HttpAsyncClient]) extends UiAgentApi:
    private val rpc = RPCClient.of[UiAgentApi]

    private def call(method: String, args: Any*): Rx[String] =
      client() match
        case Some(c) =>
          rpc.callAsync[String](c, method, args)
        case None =>
          Rx.exception(RPCStatus.UNAVAILABLE_I2.newException("No window is open"))

    override def snapshot(): Rx[String] = call("snapshot")
    override def query(
        kind: Option[String],
        text: Option[String],
        ref: Option[String]
    ): Rx[String] = call("query", kind, text, ref)

    override def act(ref: String, action: String, value: Option[String]): Rx[String] = call(
      "act",
      ref,
      action,
      value
    )

    override def changes(since: Long): Rx[String] = call("changes", since)

  end Forwarder

  // The client of the window the bridge talks to; a new window replaces it
  private var current: Option[HttpAsyncClient] = None
  private var ipcMain: js.Dynamic              = js.Dynamic.literal()
  private var server: Option[HttpServer]       = None

  /**
    * Start the bridge and resolve with `{ url, token }` once it listens. Call [[attach]] with each
    * window's `webContents` so the bridge talks to it.
    */
  @JSExportTopLevel("startAgentBridge")
  def start(ipcMain: js.Dynamic): js.Promise[js.Object] =
    // Starting again replaces the running server instead of leaking it
    server.foreach(_.stop())
    this.ipcMain = ipcMain
    val token = newToken()
    val s     = Http
      .server
      .withHost("127.0.0.1")
      .withPort(0)
      .withRxHandler(handler(Forwarder(() => current), token))
      .start()
    server = Some(s)
    val ready = js.Promise[js.Object] { (resolve, reject) =>
      s.whenReady
        .map { ready =>
          resolve(
            js.Dynamic
              .literal(url = s"http://127.0.0.1:${ready.localPort}${Path}", token = token)
              .asInstanceOf[js.Object]
          )
        }
        .recover { case e: Throwable =>
          reject(js.Error(s"Failed to start the agent bridge: ${e.getMessage}"))
        }
        .run()
    }
    ready

  /**
    * Send the bridge's calls to this window's renderer. Calls never time out: an `act` on a
    * `UiAgent.confirm` component waits for the human, and a timeout would report it failed while it
    * could still apply. A closed or reloaded window still ends every waiting call.
    */
  @JSExportTopLevel("attachAgentBridge")
  def attach(webContents: js.Dynamic): Unit =
    current.foreach(_.close())
    current = Some(
      ElectronMain.newAsyncClient(ipcMain, webContents, ElectronMainChannelConfig().noTimeout)
    )

end AgentBridge
