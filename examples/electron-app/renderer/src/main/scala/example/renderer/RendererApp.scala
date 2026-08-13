package example.renderer

import example.api.{CounterApi, CounterState}
import org.scalajs.dom
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import wvlet.uni.rx.{Rx, RxVar}
import wvlet.uni.surface.Surface

import scala.language.implicitConversions
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The renderer (UI) process. It installs the Electron IPC channel so that every Uni HTTP/RPC call
  * is tunneled to the main process through the preload bridge, then renders a small counter UI
  * whose buttons drive the [[CounterApi]].
  */
object RendererApp:

  @JSExportTopLevel("main")
  def main(): Unit =
    // Point Uni's HTTP client at the Electron IPC bridge exposed by the preload script.
    ElectronRenderer.install()
    CounterUI().renderTo("app")

end RendererApp

/**
  * The counter UI, built with Rx HTML (`wvlet.uni.dom`). The count is held in an [[RxVar]] and
  * embedded in the markup, so the display re-renders in place whenever an RPC result updates it.
  */
class CounterUI extends RxElement:

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

  // The counter value shown in the display. Updating it re-renders the number in place.
  private val count = Rx.variable(0)

  // Handle to the display element, used to briefly scale it up on each change.
  private val displayRef = DomRef[dom.html.Paragraph]()

  override def render: RxElement =
    def actionButton(label: String, colorClasses: String)(action: Rx[CounterState]): RxElement =
      button(
        cls ->
          s"cursor-pointer rounded-lg px-4 py-2 font-medium text-white shadow transition-colors ${colorClasses}",
        onclick -> { () =>
          action.run(show)
        },
        label
      )

    div(
      cls -> "w-80 rounded-2xl bg-slate-800 p-8 text-center shadow-2xl ring-1 ring-white/10",
      h1(cls -> "text-xl font-semibold text-slate-100", "Uni Counter"),
      p(
        cls -> "mt-1 mb-7 text-xs uppercase tracking-widest text-slate-400",
        "RPC over Electron IPC"
      ),
      p(
        ref -> displayRef,
        cls -> "mb-8 text-7xl font-bold tabular-nums text-indigo-400 transition-transform",
        count.map(c => c.toString)
      ),
      div(
        cls -> "flex items-center justify-center gap-3",
        actionButton("+1", "bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700")(increment(1)),
        actionButton("+10", "bg-indigo-700 hover:bg-indigo-600 active:bg-indigo-800")(
          increment(10)
        ),
        actionButton("Reset", "bg-slate-600 hover:bg-slate-500 active:bg-slate-700")(reset())
      )
    )

  end render

  // Reflect a state snapshot into the reactive count (with a tiny pop animation).
  private def show(state: CounterState): Unit =
    count := state.value
    displayRef.foreach { display =>
      display.classList.add("scale-110")
      dom.window.setTimeout(() => display.classList.remove("scale-110"), 120)
    }

  // Load the initial value from the main process once the UI is mounted.
  override def onMount(node: Any): Unit = get().run(show)

end CounterUI
