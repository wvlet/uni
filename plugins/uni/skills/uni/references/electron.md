# Electron desktop apps (RPC over IPC)

`wvlet.uni.electron` is **Scala.js only**. It carries Uni [RPC](rpc.md) over
Electron IPC: the main process serves `RPCRouter`s, and the renderer's async HTTP
client sends through a preload bridge. No HTTP server or open port is involved.
Service traits and clients are the same as for HTTP RPC.

Full reference: https://wvlet.org/uni/http/electron.md (runnable app:
https://github.com/wvlet/uni/tree/main/examples/electron-app)

## Shared service

```scala skip
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver

case class CounterState(value: Int) derives Weaver

trait CounterApi:
  def get(): Rx[CounterState]
  def increment(amount: Int): Rx[CounterState]

class CounterApiImpl extends CounterApi:
  private var value = 0
  def get(): Rx[CounterState] = Rx.single(CounterState(value))
  def increment(amount: Int): Rx[CounterState] = Rx.single {
    value += amount
    CounterState(value)
  }
```

## Main process

Electron objects are handed in from JavaScript; the Scala code never calls
`require("electron")`.

```scala skip
import wvlet.uni.electron.{ElectronRPCServer, ElectronRPCServerConfig}
import wvlet.uni.http.rpc.RPCRouter
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

object MainProcess:
  @JSExportTopLevel("wireMainProcess")
  def wireMainProcess(ipcMain: js.Dynamic): Unit =
    ElectronRPCServer.serve(
      ipcMain,
      ElectronRPCServerConfig().withAllowedOrigins("file://", "http://localhost:5173"),
      RPCRouter.of[CounterApi](CounterApiImpl())
    )
```

```js
// main.js: app.whenReady().then(() => { wireMainProcess(ipcMain); createWindow() })
// preload.js (keep context isolation on):
contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload)
})
```

- `ElectronRPCServer.serve(ipcMain, routers*)` uses the default config: channel
  `uni-rpc`, top-level frames only. Calls from rejected senders fail with
  `PERMISSION_DENIED_U14`.
- Config: `withAllowedOrigins(prefixes*)`, `withAllowSubFrames(true)`,
  `withSenderFilter(frame => ...)`, `withChannel(name)`.

## Renderer

Call `ElectronRenderer.install()` once; afterwards every `newAsyncClient` (and
generated RPC `AsyncClient`) goes over IPC. A renderer has no synchronous IPC, so
`newSyncClient` calls throw.

```scala skip
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import scala.scalajs.js.annotation.JSExportTopLevel

object RendererApp:
  private val rpc = RPCClient.of[CounterApi]

  @JSExportTopLevel("main")
  def main(): Unit =
    ElectronRenderer.install() // reads window.uniRPC; pass another bridge object if renamed
    val client = Http.client.newAsyncClient
    rpc.callAsync[CounterState](client, "increment", Seq(1)).run { s =>
      println(s"counter = ${s.value}")
    }
```

## Testing without Electron

`wvlet.uni.electron.testing.ElectronTestbed` wires a fake `ipcMain` to a fake
bridge in memory, so the IPC round trip runs in a Scala.js `UniTest`:

```scala skip
import wvlet.uni.electron.testing.ElectronTestbed
import wvlet.uni.http.rpc.{RPCClient, RPCRouter}
import wvlet.uni.test.*

class CounterIpcTest extends UniTest:
  test("increment over IPC") {
    val client = ElectronTestbed.serve(RPCRouter.of[CounterApi](CounterApiImpl())).newAsyncClient
    RPCClient
      .of[CounterApi]
      .callAsync[CounterState](client, "increment", Seq(2))
      .map(_.value shouldBe 2)
  }
```
