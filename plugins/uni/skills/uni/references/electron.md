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

For streaming calls (`rpc.stream`, see `rpc.md`), the bridge also needs `stream`.
The main process answers on `uni-rpc-stream-event` with `(id, message)` until an
`end` or `error` message:

```js
const streams = new Map()
// Unique per page load: a reload keeps the webContents, and a stream from the old page may still run
const pageId = Math.random().toString(36).slice(2)
let nextStreamId = 0
ipcRenderer.on('uni-rpc-stream-event', (_event, id, message) => {
  const onMessage = streams.get(id)
  if (!onMessage) return
  if (message.kind === 'end' || message.kind === 'error') streams.delete(id)
  onMessage(message)
})
contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload),
  stream: (payload, onMessage) => {
    const id = `${pageId}-${++nextStreamId}`
    streams.set(id, onMessage)
    ipcRenderer.send('uni-rpc-stream', id, payload)
    // Called when the renderer cancels the call: the server stops the stream
    return () => {
      if (streams.delete(id)) ipcRenderer.send('uni-rpc-stream-cancel', id)
    }
  }
})
```

- `ElectronRPCServer.serve(ipcMain, routers*)` uses the default config: channel
  `uni-rpc`, top-level frames only. Calls from rejected senders fail with
  `PERMISSION_DENIED_U14`. Streams get the same checks.
- The server cancels a stream when the renderer cancels its `Rx` (through the
  function `stream` returns), when the main frame loads another page (including
  a reload), and when the window's `webContents` is destroyed.
- Config: `withAllowedOrigins(prefixes*)`, `withAllowSubFrames(true)`,
  `withSenderFilter(frame => ...)`, `withChannel(name)`.

## Renderer

Call `ElectronRenderer.install()` once; afterwards every `newAsyncClient` (and
generated RPC `AsyncClient`) goes over IPC. A renderer has no synchronous IPC, so
`newSyncClient` calls throw. For a renderer that also runs in a plain browser, use
`ElectronRenderer.installIfAvailable()`: it returns `false` and leaves the default
`fetch` channel when `window.uniRPC` is missing.

```scala skip
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import scala.scalajs.js.annotation.JSExportTopLevel

object RendererApp:
  @JSExportTopLevel("main")
  def main(): Unit =
    ElectronRenderer.install() // reads window.uniRPC; pass another bridge object if renamed
    // Create the HTTP client after install(), which sets the channel factory
    val rpc = RPCClient.of[CounterApi].newAsyncClient(Http.client.newAsyncClient)
    rpc.call[CounterState]("increment", 1).run { s =>
      println(s"counter = ${s.value}")
    }
```

## Calling the renderer from the main process

For services that live in the page (such as a uni-ui `UiAgent`), the main process
calls the renderer over a reverse channel: `uni-rpc-reverse` carries `(id, request)`
to a `webContents`, and the renderer answers `(id, response)` on
`uni-rpc-reverse-reply`. The preload adds `serve`:

```js
let reverseHandler = null
ipcRenderer.on('uni-rpc-reverse', async (_event, id, payload) => {
  let response = { status: 503, headers: {}, body: '' } // not served yet: UNAVAILABLE_I2
  try {
    if (reverseHandler) response = await reverseHandler(payload)
  } catch (e) {
    response = { status: 500, headers: {}, body: '' } // always reply
  }
  ipcRenderer.send('uni-rpc-reverse-reply', id, response)
})
// add to the uniRPC object: serve: (handler) => { reverseHandler = handler }
```

```scala skip
// Renderer
ElectronRenderer.serve(RPCRouter.of[PageApi](PageApiImpl()))
// Main process (ipcMain, webContents handed in from JS)
val client = ElectronMain.newAsyncClient(ipcMain, webContents)
RPCClient.of[PageApi].newAsyncClient(client).call[String]("title")
```

- Only replies from that `webContents`' top-level frame count. A waiting call
  fails with `UNAVAILABLE_I2` when the window closes or reloads, and with
  `DEADLINE_EXCEEDED_I4` after `ElectronMainChannelConfig().withTimeoutMillis(ms)`
  (default 120 s). No retries, no streaming in this direction.
- Testing: `ElectronRenderer.serve(testbed.bridge(), router)`, then
  `testbed.newMainClient()`.
- uni-harness serves `UiAgentApi` this way and exposes it as a token-guarded MCP
  endpoint on 127.0.0.1 when started with `--agent-bridge` (`pnpm dev:agent`).

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
      .newAsyncClient(client)
      .call[CounterState]("increment", 2)
      .map(_.value shouldBe 2)
  }
```
