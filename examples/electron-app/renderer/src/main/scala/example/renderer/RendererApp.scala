package example.renderer

import example.api.{CounterApi, CounterState}
import org.scalajs.dom
import org.scalajs.dom.html
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.Rx
import wvlet.uni.surface.Surface

import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The renderer (UI) process. It installs the Electron IPC channel so that every Uni HTTP/RPC call
  * is tunneled to the main process through the preload bridge, then renders a small counter UI whose
  * buttons drive the [[CounterApi]].
  */
object RendererApp:

  // A reusable RPC engine for CounterApi. The generated/manual stub below adds typed methods.
  private val rpc: RPCClient = RPCClient.build(
    Surface.of[CounterApi],
    Surface.methodsOf[CounterApi]
  )

  // The async client routes through whatever channel factory is installed (Electron IPC, below).
  private lazy val client = Http.client.newAsyncClient

  private def get(): Rx[CounterState] = rpc.callAsync[CounterState](client, "get", Seq.empty)
  private def increment(amount: Int): Rx[CounterState] =
    rpc.callAsync[CounterState](client, "increment", Seq(amount))
  private def reset(): Rx[CounterState] = rpc.callAsync[CounterState](client, "reset", Seq.empty)

  @JSExportTopLevel("main")
  def main(): Unit =
    // Point Uni's HTTP client at the Electron IPC bridge exposed by the preload script.
    ElectronRenderer.install()
    renderUI()

  private def renderUI(): Unit =
    val app   = dom.document.getElementById("app")
    val title = dom.document.createElement("h1")
    title.textContent = "Uni Electron Counter"

    val display = dom.document.createElement("p").asInstanceOf[html.Paragraph]
    display.id = "counter-value"
    display.textContent = "…"

    def button(label: String)(onClick: => Unit): html.Button =
      val b = dom.document.createElement("button").asInstanceOf[html.Button]
      b.textContent = label
      b.onclick = _ => onClick
      b

    // Reflect a state snapshot into the UI.
    def show(state: CounterState): Unit = display.textContent = state.value.toString

    val incBtn   = button("+1")(increment(1).run(show))
    val inc10Btn = button("+10")(increment(10).run(show))
    val resetBtn = button("Reset")(reset().run(show))

    app.appendChild(title)
    app.appendChild(display)
    app.appendChild(incBtn)
    app.appendChild(inc10Btn)
    app.appendChild(resetBtn)

    // Load the initial value from the main process.
    get().run(show)

  end renderUI

end RendererApp
