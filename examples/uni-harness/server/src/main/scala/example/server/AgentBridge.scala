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

import wvlet.uni.ai.agent.UiAgentApi
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
import wvlet.uni.http.ServiceStatus
import wvlet.uni.json.JSON
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.ai.mcp.MCPServer
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
  * Each MCP tool call goes to one window's renderer over the main→renderer IPC channel, where the
  * renderer serves `UiAgentApi` with `ElectronRenderer.serve`. The `windows` tool lists the open
  * windows, and every other tool takes an optional window id (see [[HarnessAgentApi]]).
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
  def handler(api: HarnessAgentApi, token: String): RxHttpHandler =
    val mcp =
      MCPServer()
        .withName("uni-harness")
        .withVersion("0.1.0")
        .withTools[HarnessAgentApi](api)
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

  /**
    * A window the bridge can send calls to: its Electron `BrowserWindow.id`, the RPC client of its
    * renderer, and live reads of its title and focus.
    */
  class AgentWindow(
      val id: Int,
      val client: HttpAsyncClient,
      title: () => String,
      focused: () => Boolean
  ):
    def currentTitle: String      = title()
    def isFocused: Boolean        = focused()
    override def toString: String = s"AgentWindow(${id})"

  /**
    * The open windows, in the order they opened. A call without a window id goes to the focused
    * window, or else to the newest one.
    */
  class AgentWindows:
    private var opened: Vector[AgentWindow] = Vector.empty

    def all: Seq[AgentWindow] = opened

    def add(w: AgentWindow): Unit = opened = opened.filterNot(_.id == w.id) :+ w

    def remove(id: Int): Unit =
      opened.find(_.id == id).foreach(_.client.close())
      opened = opened.filterNot(_.id == id)

    /** The window a call without an id goes to. */
    def default: Option[AgentWindow] = opened.findLast(_.isFocused).orElse(opened.lastOption)

    def resolve(window: Option[Int]): Either[Exception, AgentWindow] =
      window match
        case Some(id) =>
          opened
            .find(_.id == id)
            .toRight(
              ServiceStatus
                .NOT_FOUND_U5
                .newException(s"No window ${id} is open; call windows to list the open windows")
            )
        case None =>
          default.toRight(ServiceStatus.UNAVAILABLE_I2.newException("No window is open"))

  end AgentWindows

  /**
    * The bridge's tools: `UiAgentApi` calls forwarded to the chosen window's renderer, plus
    * `windows`. Each result wraps the renderer's JSON as `{"window": id, "result": ...}`, so the
    * agent sees which window answered.
    */
  class Forwarder(opened: AgentWindows) extends HarnessAgentApi:
    private val rpc = RPCClient.of[UiAgentApi]

    private def call(window: Option[Int], method: String, args: Any*): Rx[String] =
      opened.resolve(window) match
        case Right(w) =>
          rpc
            .callAsync[String](w.client, method, args)
            .map(r => s"""{"window":${w.id},"result":${r}}""")
        case Left(e) =>
          Rx.exception(e)

    override def windows(): Rx[String] =
      val default = opened.default.map(_.id)
      val items   = opened
        .all
        .map { w =>
          s"""{"id":${w.id},"title":"${JSON.quoteJSONString(w.currentTitle)}","focused":${w
              .isFocused},"default":${default.contains(w.id)}}"""
        }
      Rx.single(items.mkString("""{"windows":[""", ",", "]}"))

    override def snapshot(window: Option[Int]): Rx[String] = call(window, "snapshot")
    override def query(
        kind: Option[String],
        text: Option[String],
        ref: Option[String],
        window: Option[Int]
    ): Rx[String] = call(window, "query", kind, text, ref)

    override def act(
        ref: String,
        action: String,
        value: Option[String],
        window: Option[Int]
    ): Rx[String] = call(window, "act", ref, action, value)

    override def changes(since: Long, window: Option[Int]): Rx[String] = call(
      window,
      "changes",
      since
    )

  end Forwarder

  private val opened: AgentWindows       = AgentWindows()
  private var ipcMain: js.Dynamic        = js.Dynamic.literal()
  private var server: Option[HttpServer] = None

  /**
    * Start the bridge and resolve with `{ url, token }` once it listens. Call [[attach]] with each
    * `BrowserWindow` so the bridge can talk to it.
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
      .withRxHandler(handler(Forwarder(opened), token))
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
    * Let the bridge send calls to this `BrowserWindow`'s renderer, until the window closes. Calls
    * never time out: an `act` on a `UiAgent.confirm` component waits for the human, and a timeout
    * would report it failed while it could still apply. A closed or reloaded window still ends
    * every waiting call. `isFocused` tells whether the user is in this window, including its
    * detached dock panels, so a call without a window id goes there.
    */
  @JSExportTopLevel("attachAgentBridge")
  def attach(win: js.Dynamic, isFocused: js.Function0[Boolean]): Unit =
    val id = win.id.asInstanceOf[Int]
    opened.add(
      AgentWindow(
        id,
        ElectronMain.newAsyncClient(
          ipcMain,
          win.webContents,
          ElectronMainChannelConfig().noTimeout
        ),
        title = () => win.getTitle().asInstanceOf[String],
        focused = () => isFocused()
      )
    )
    win.on("closed", () => opened.remove(id))

end AgentBridge
