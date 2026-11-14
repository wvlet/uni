# Desktop Apps (Electron)

Build [Electron](https://www.electronjs.org/) desktop apps with Uni and Scala.js, and let the UI
call services in the main process using Uni's [RPC](/http/rpc) — tunneled over Electron's IPC
instead of a network socket. No HTTP server, no open ports.

The transport lives in the `wvlet.uni.electron` package and is **Scala.js only**
(`uni/.js`). A complete, runnable reference app is in
[`examples/electron-app`](https://github.com/wvlet/uni/tree/main/examples/electron-app).

::: tip New to this?
Start with the [Electron Tutorial](/http/electron-tutorial) — a step-by-step setup from an empty
directory. This page is the API reference for what the tutorial wires together.
:::

## How it fits together

Electron runs three contexts. Uni occupies two of them; the third is a tiny hand-written bridge:

```mermaid
sequenceDiagram
    participant R as Renderer · Chromium
    participant P as Preload bridge
    participant M as Main · Node.js
    Note over R: ElectronRenderer.install()<br/>Http.client.newAsyncClient
    R->>P: window.uniRPC.request(payload)
    P->>M: ipcRenderer.invoke('uni-rpc', payload)
    Note over M: RPCDispatcher → RPCRouter<br/>your service impl
    M-->>P: response payload
    P-->>R: Promise resolves
```

- **Renderer** serializes each RPC request to a plain object and calls the preload bridge.
- **Preload** forwards it via `ipcRenderer.invoke` (context isolation stays on).
- **Main** dispatches it through the same transport-neutral `RPCDispatcher` that the HTTP server
  uses, and resolves the response payload.

Because the wire format is identical to HTTP RPC, your existing service traits and clients work
unchanged.

## The shared service

Define the service trait once and share it between both processes. Returning `Rx[A]` keeps calls
asynchronous, which suits IPC:

```scala
import wvlet.uni.rx.Rx

case class CounterState(value: Int)

trait CounterApi:
  def get(): Rx[CounterState]
  def increment(amount: Int): Rx[CounterState]
  def reset(): Rx[CounterState]
```

## Main process

Implement the service and register it on Electron's `ipcMain`. Electron objects are passed in as
values — this Scala module never has to `require("electron")`:

```scala
import wvlet.uni.electron.ElectronRPCServer
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.rx.Rx
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

class CounterApiImpl extends CounterApi:
  private var value = 0
  def get(): Rx[CounterState]                  = Rx.single(CounterState(value))
  def increment(amount: Int): Rx[CounterState] = { value += amount; Rx.single(CounterState(value)) }
  def reset(): Rx[CounterState]                = { value = 0; Rx.single(CounterState(value)) }

object MainProcess:
  @JSExportTopLevel("wireMainProcess")
  def wireMainProcess(ipcMain: js.Dynamic): Unit =
    ElectronRPCServer.serve(ipcMain, RPCRouter.of[CounterApi](CounterApiImpl()))
```

Pass several routers to serve multiple services over the one channel:
`ElectronRPCServer.serve(ipcMain, RPCRouter.of[A](a), RPCRouter.of[B](b))`.

Electron delivers IPC calls from every frame of every window, including embedded iframes. By
default the server accepts calls only from top-level frames; a rejected call fails with
`PERMISSION_DENIED_U14`. Restrict callers to your own pages with `ElectronRPCServerConfig`:

```scala
import wvlet.uni.electron.ElectronRPCServerConfig

ElectronRPCServer.serve(
  ipcMain,
  ElectronRPCServerConfig()
    .withAllowedOrigins("file://", "http://localhost:5173"), // packaged app + Vite dev server
  RPCRouter.of[CounterApi](CounterApiImpl())
)
```

An origin matches the sender frame URL as a prefix at a path boundary, so `http://localhost:5173`
does not match `http://localhost:51730`. `withSenderFilter(frame => ...)` adds a custom check of the
`WebFrameMain`, and `withAllowSubFrames(true)` lets iframes call.

The JS main entry hands `ipcMain` over once the app is ready:

```js
import { app, ipcMain } from 'electron'
import { wireMainProcess } from 'scalajs:main.js'

app.whenReady().then(() => {
  wireMainProcess(ipcMain)
  // … createWindow() …
})
```

## Preload bridge

The function the renderer transport expects for ordinary calls. Keep context isolation on:

```js
import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload)
})
```

### Streaming calls

[Streaming calls](./rpc#streaming-results) (`rpc.stream`) need a second function, `stream`. The main
process answers each call on `uni-rpc-stream-event` with the call's id and a message, until an `end`
or `error` message. One listener serves every call:

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

Make the ids unique per page load, as `pageId` does: a reload keeps the window's `webContents`, so a
stream the old page started can still be sending. `ElectronRPCServer.serve` listens on the stream
channel too, with the same sender checks as `request`, and replies to the frame that called.

The function `stream` returns cancels the call. The renderer channel calls it when the `Rx` from
`rpc.stream` is cancelled (an unsubscribe, or `take(n)`), and the server then cancels the method's
`Rx`. The server also cancels a window's streams when its main frame starts loading another page,
including a reload, and when the window's `webContents` is destroyed. An in-page navigation (a hash
change or `history.pushState`) keeps them running. A bridge whose `stream` returns nothing still
works, but then the server stream runs until it ends or the page goes away.

Without `stream` in the bridge, a streaming call fails with a message that says what's missing;
unary calls work as before.

## Renderer

Call `ElectronRenderer.install()` once at startup. Afterward every `Http.client.newAsyncClient`
(and any generated RPC `AsyncClient`) sends through the IPC bridge:

```scala
import wvlet.uni.electron.ElectronRenderer
import wvlet.uni.http.Http
import wvlet.uni.http.rpc.RPCClient
import scala.scalajs.js.annotation.JSExportTopLevel

object RendererApp:
  // Lazy: the HTTP client must be created after install() sets the channel factory
  private lazy val rpc = RPCClient.of[CounterApi].newAsyncClient(Http.client.newAsyncClient)

  @JSExportTopLevel("main")
  def main(): Unit =
    ElectronRenderer.install() // wires window.uniRPC as the HTTP channel factory
    rpc.call[CounterState]("increment", 1).run { state =>
      println(s"counter = ${state.value}")
    }
```

`install()` defaults to `window.uniRPC`; pass a different bridge object if your preload exposes
another name: `ElectronRenderer.install(myBridge)`.

When the same renderer also runs in a plain browser, call `ElectronRenderer.installIfAvailable()`
instead. It installs the IPC channel when `window.uniRPC` exists and returns `true`. Otherwise it
returns `false`, and the client keeps its default `fetch` channel to the web server:

```scala
val overIPC = ElectronRenderer.installIfAvailable()
```

::: tip Async only
A sandboxed renderer has no synchronous IPC, so the Electron channel implements only the async path.
Use `Http.client.newAsyncClient`; `newSyncClient` throws.
:::

## Calling the renderer from the main process

Some services live in the renderer because they need the page, such as a
[`UiAgent`](../dom/agents) that reads the DOM. The main process calls them over a reverse
channel with the same payloads: it sends `(id, request)` on `uni-rpc-reverse` to one window's
`webContents`, and the renderer answers `(id, response)` on `uni-rpc-reverse-reply`.

The preload needs one more function, `serve`. It listens from the start, so a call that arrives
before the renderer serves gets `503` (`UNAVAILABLE_I2`) at once instead of waiting:

```js
let reverseHandler = null
ipcRenderer.on('uni-rpc-reverse', async (_event, id, payload) => {
  let response = { status: 503, headers: {}, body: '' }
  try {
    if (reverseHandler) response = await reverseHandler(payload)
  } catch (e) {
    // Always reply, so the main process never waits for an answer that won't come
    response = { status: 500, headers: {}, body: '' }
  }
  ipcRenderer.send('uni-rpc-reverse-reply', id, response)
})

contextBridge.exposeInMainWorld('uniRPC', {
  request: (payload) => ipcRenderer.invoke('uni-rpc', payload),
  serve: (handler) => {
    reverseHandler = handler
  }
})
```

The renderer serves routers, and the main process calls them through a client bound to the window:

```scala
// Renderer
ElectronRenderer.serve(RPCRouter.of[PageApi](PageApiImpl()))

// Main process: ipcMain and win.webContents are handed in from JavaScript
val client = ElectronMain.newAsyncClient(ipcMain, webContents)
RPCClient.of[PageApi].newAsyncClient(client).call[String]("title")
```

- A reply counts only when it comes from that `webContents`' top-level frame and names a waiting
  call.
- A waiting call fails with `UNAVAILABLE_I2` when the window closes or its page reloads, and with
  `DEADLINE_EXCEEDED_I4` after `ElectronMainChannelConfig().withTimeoutMillis(ms)` (120 seconds by
  default; `noTimeout` waits as long as the page lives).
- The client does not retry, so a call cut off by a reload never runs twice. Streaming is not
  supported in this direction.
- `ElectronTestbed` plays the renderer side too: `ElectronRenderer.serve(testbed.bridge(), router)`,
  then call it with `testbed.newMainClient()`.

The [uni-harness example](https://github.com/wvlet/uni/tree/main/examples/uni-harness) uses this
for its agent bridge. `pnpm dev:agent` starts a local MCP endpoint in the main process. It listens
on 127.0.0.1, requires a bearer token made at startup, and forwards each `UiAgentApi` tool call to
the renderer's `UiAgent`. Without `--agent-bridge` there is no endpoint.

## Build pipeline

The example uses [`electron-vite`](https://electron-vite.org/) with
[`@scala-js/vite-plugin-scalajs`](https://www.scala-js.org/doc/tutorial/scalajs-vite.html). The
plugin links a Scala.js sbt project and exposes it to the bundle as `import 'scalajs:main.js'`; its
`projectID` selects which project (here, `main` and `renderer`):

```js
// electron.vite.config.mjs
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig({
  main:     { plugins: [externalizeDepsPlugin(), scalaJSPlugin({ cwd: '.', projectID: 'main' })] },
  preload:  { plugins: [externalizeDepsPlugin()] },
  renderer: { plugins: [scalaJSPlugin({ cwd: '.', projectID: 'renderer' })] }
})
```

Both Scala.js projects emit ES modules and export functions (no `main` initializer):

```scala
scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
scalaJSUseMainModuleInitializer := false
```

Then:

```bash
pnpm dev       # electron-vite: compile Scala.js, start the renderer dev server, launch Electron
pnpm package   # electron-vite build, then electron-builder → installers in dist/
```

Packaging is handled by [`electron-builder`](https://www.electron.build/) (dmg / nsis / AppImage).

::: warning JS dependency
Uni's Scala.js code uses `java.security.SecureRandom` (via ULID in the serialization layer), so a
desktop app pulls in `scalajs-java-securerandom` transitively from `uni`. If you depend on a Uni
release older than this feature, add it yourself:

```scala
libraryDependencies +=
  ("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13)
```
:::

## App size

An Electron app ships its own copy of Chromium. That is where most of the size comes from, and it is
also what Electron gives you: the same up-to-date engine, rendering, and DevTools on every OS, with
no dependency on the browser engine the user's OS happens to have. Measured on the example app
(Electron 45, macOS arm64):

| Part                                         | Size     | Can you drop it?                                     |
| -------------------------------------------- | -------- | ---------------------------------------------------- |
| Chromium core (`Electron Framework` binary)  | 197 MB   | No                                                   |
| SwiftShader (software GPU fallback)          | 16 MB    | Only if every user has a GPU; VMs and GPU-less machines may fail to render |
| ICU data, `.pak` resources, V8 snapshot      | 25 MB    | No                                                   |
| Locale folders (`*.lproj`), all languages    | 9 MB     | Yes — keep only the languages your UI supports       |
| Uni + app code (`app.asar`, Scala.js)        | 3.9 MB   | Minify it (1.8 MB minified)                          |

The example applies the safe trims:

```yaml
# electron-builder.yml
compression: maximum
electronLanguages:
  - en
```

```js
// electron.vite.config.mjs — Scala.js output is not minified by default in main/renderer builds
main:     { plugins: [...], build: { minify: true } },
renderer: { plugins: [...], build: { minify: true } }
```

That takes the macOS DMG from 121 MB to 109 MB (−10%), and the installed app from 262 MiB to
251 MiB. The rest is Chromium itself. Other levers:

- **Build one architecture per installer.** A universal macOS build (arm64 + x64) carries two
  copies of Chromium. `electron-builder` builds for the host architecture unless you ask for
  `universal`.
- **Choose the shell for the tradeoff you need.** Electron fits apps that need a predictable engine.
  When download size matters more than engine consistency, shells that use the OS's own WebView,
  such as [Tauri](https://tauri.app/), are a good choice. Uni RPC services also run over plain HTTP
  ([`Http.server`](/http/)), not only over Electron IPC.

See the full project — sbt build, Vite config, preload, packaging — in
[`examples/electron-app`](https://github.com/wvlet/uni/tree/main/examples/electron-app).

For a larger template, [`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness)
is a chat workspace shell (icon rail, foldable sidebar, chat with a composer, right dock) whose
renderer and service run unchanged as an Electron app and as a web app: the renderer uses IPC when
the preload bridge is present and fetch otherwise.
