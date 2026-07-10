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
  * is tunneled to the main process through the preload bridge, then renders a small counter UI
  * whose buttons drive the [[CounterApi]].
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
  private def increment(amount: Int): Rx[CounterState] = rpc.callAsync[CounterState](
    client,
    "increment",
    Seq(amount)
  )

  private def reset(): Rx[CounterState] = rpc.callAsync[CounterState](client, "reset", Seq.empty)

  @JSExportTopLevel("main")
  def main(): Unit =
    // Point Uni's HTTP client at the Electron IPC bridge exposed by the preload script.
    ElectronRenderer.install()
    renderUI()

  // Small helper to create an element of a known HTML type with Tailwind classes + text.
  private def el[E <: html.Element](tag: String, classes: String, text: String = ""): E =
    val e = dom.document.createElement(tag).asInstanceOf[E]
    e.className = classes
    if text.nonEmpty then
      e.textContent = text
    e

  private def renderUI(): Unit =
    val app = dom.document.getElementById("app")

    // Card container
    val card = el[html.Div](
      "div",
      "w-80 rounded-2xl bg-slate-800 p-8 text-center shadow-2xl ring-1 ring-white/10"
    )

    val title    = el[html.Heading]("h1", "text-xl font-semibold text-slate-100", "Uni Counter")
    val subtitle = el[html.Paragraph](
      "p",
      "mt-1 mb-7 text-xs uppercase tracking-widest text-slate-400",
      "RPC over Electron IPC"
    )

    val display = el[html.Paragraph](
      "p",
      "mb-8 text-7xl font-bold tabular-nums text-indigo-400 transition-transform",
      "…"
    )
    display.id = "counter-value"

    def button(label: String, classes: String)(onClick: => Unit): html.Button =
      val b = el[html.Button](
        "button",
        s"cursor-pointer rounded-lg px-4 py-2 font-medium text-white shadow transition-colors ${classes}",
        label
      )
      b.onclick = _ => onClick
      b

    // Reflect a state snapshot into the UI (with a tiny pop animation).
    def show(state: CounterState): Unit =
      display.textContent = state.value.toString
      display.className =
        "mb-8 text-7xl font-bold tabular-nums text-indigo-400 transition-transform scale-110"
      dom
        .window
        .setTimeout(
          () =>
            display.className =
              "mb-8 text-7xl font-bold tabular-nums text-indigo-400 transition-transform",
          120
        )

    val row = el[html.Div]("div", "flex items-center justify-center gap-3")
    row.appendChild(
      button("+1", "bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700")(increment(1).run(show))
    )
    row.appendChild(
      button("+10", "bg-indigo-700 hover:bg-indigo-600 active:bg-indigo-800")(
        increment(10).run(show)
      )
    )
    row.appendChild(
      button("Reset", "bg-slate-600 hover:bg-slate-500 active:bg-slate-700")(reset().run(show))
    )

    card.appendChild(title)
    card.appendChild(subtitle)
    card.appendChild(display)
    card.appendChild(row)
    app.appendChild(card)

    // Load the initial value from the main process.
    get().run(show)

  end renderUI

end RendererApp
