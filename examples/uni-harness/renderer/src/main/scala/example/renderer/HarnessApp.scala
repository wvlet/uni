package example.renderer

import example.api.ChatApiClient
import org.scalajs.dom
import wvlet.uni.agent.UiAgentApi
import wvlet.uni.dom.all.renderTo
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.ui.agent.UiAgent

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The renderer entry point, shared by the Electron app and the browser app. It only wires things
  * together: the transport, the typed client, the stores, and the shell. The one difference between
  * the two apps is the transport, chosen once here.
  */
object HarnessApp:

  @JSExportTopLevel("main")
  def main(): Unit =
    val window = dom.window.asInstanceOf[js.Dynamic]
    // The Electron preload script exposes `window.uniRPC`. In a browser it is absent, and Uni's
    // HTTP client keeps its default fetch channel, calling the same-origin web server.
    val transport =
      if ElectronRenderer.installIfAvailable() then
        Transport.ElectronIPC
      else
        Transport.HttpFetch

    val root = dom.document.documentElement.classList
    if transport == Transport.ElectronIPC then
      root.add("is-electron")
      if js.typeOf(window.uniHarness) != "undefined" &&
        window.uniHarness.platform.asInstanceOf[String] == "darwin"
      then
        root.add("mac-inset")

    val log   = RpcLog()
    val api   = TrackedChatApi(ChatApiClient(Http.client.newAsyncClient), log)
    val store = ChatStore(api, explain(transport))
    Shell(Layout.persistent(), store, log, transport).renderTo("app")
    serveAgent(window)

  /**
    * When the app runs with `--agent-bridge`, let an AI agent read and operate the UI: install
    * `UiAgent` and serve its `UiAgentApi` to the main process, whose MCP endpoint forwards each
    * tool call here. Without the flag the preload sets no `agentBridge`, and nothing is installed.
    */
  private def serveAgent(window: js.Dynamic): Unit =
    val bridge = window.uniHarness
    if js.typeOf(bridge) != "undefined" && bridge.agentBridge.asInstanceOf[Any] == true then
      val agent = UiAgent.install()
      ElectronRenderer.serve(RPCRouter.of[UiAgentApi](agent.api))

  /**
    * A 404 over HTTP means no ChatApi server is behind this page: typically a browser opened on the
    * Electron renderer's dev server (port 5173). Say where to go instead.
    */
  private def explain(transport: Transport)(method: String, e: Throwable): String =
    val message = ChatStore.describe(method, e)
    if transport == Transport.HttpFetch && message.contains("Not Found") then
      s"${method} failed: no ChatApi server answers at ${dom.window.location.host}. " +
        "In a browser, run `pnpm dev:web` and open http://localhost:5180."
    else
      message

end HarnessApp
