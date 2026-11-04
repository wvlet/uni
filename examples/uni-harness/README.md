# Uni Harness: a chat workspace for Electron and the browser

A desktop-style app shell built with **Uni + Scala.js**: an icon rail, a foldable left sidebar, a
chat thread with a composer, and a right dock. The same renderer and the same service code run as
an [Electron](https://www.electronjs.org/) app and as a web app in a browser. Copy it as a starting
point for a chat or agent tool.

```mermaid
flowchart LR
    R["Renderer · Scala.js<br/>Shell, ChatView, Composer"]
    R -- "window.uniRPC present:<br/>Electron IPC" --> M["Electron main<br/>wireMainProcess(ipcMain)"]
    R -- "absent:<br/>fetch POST /example.api.ChatApi/*" --> W["Node · Http.server<br/>startWebServer(port)"]
    M --> S["ChatService<br/>(server module)"]
    W --> S
```

## What it shows

- **One service, two transports.** `ChatApi` (in `api/`) is implemented once by `ChatService` (in
  `server/`). Electron's main process serves it with `ElectronRPCServer.serve`; the web build serves
  it with `Http.server` on Node. The renderer installs `ElectronRenderer` when the preload bridge
  exists and otherwise keeps Uni's default fetch channel, so the calling code is identical.
- **A workspace layout in Rx HTML.** `Shell`, `IconRail`, `Sidebar`, `ChatView`, `Composer`, and
  `Dock` are small `RxElement` classes in
  [`Shell.scala`](renderer/src/main/scala/example/renderer/Shell.scala).
- **Layout state that persists.** The open/closed state of the sidebar and the dock, and the active
  rail view, are `Storage.local` variables, so they survive a reload.
- **Keyboard shortcuts.** `Keyboard.bindAll`: ⌘B / Ctrl+B folds the sidebar, ⌘⇧B / Ctrl+Shift+B
  toggles the dock.
- **A composer that respects IME input.** Enter sends, Shift+Enter adds a newline, and Enter during
  IME composition (Japanese, Chinese, Korean input) confirms the conversion instead of sending.

The assistant is a mock that echoes your message after a short pause, so the example needs no API
key. Replace `ChatService.respond` with a call to your model or agent.

## Layout

| Path | What it is |
| --- | --- |
| `api/` | Scala.js: the `ChatApi` RPC trait and models |
| `server/` | Scala.js: `ChatService`, plus `wireMainProcess` (IPC) and `startWebServer` (HTTP) |
| `renderer/` | Scala.js: the UI and the transport choice |
| `src/main/index.js` | Electron main entry: creates the window, hands `ipcMain` to Scala |
| `src/preload/index.js` | `contextBridge` bridge: `window.uniRPC.request → ipcRenderer.invoke` |
| `src/renderer/` | `index.html`, the JS entry, and the Tailwind theme (light and dark) |
| `src/web/server.js` | Node entry for the web build: serves the UI and `ChatApi` |
| `electron.vite.config.mjs` | The Electron build (electron-vite + `vite-plugin-scalajs`) |
| `vite.web.config.mjs` | The web build: dev server with the `ChatApi` server, and the production bundles |

## Prerequisites

- JDK 17+ and `sbt` on your `PATH` (the Vite plugins shell out to `sbt`)
- Node.js 22+ and `pnpm`
- A locally published Uni snapshot for Scala.js:

  ```bash
  # from the Uni repo root
  ./sbt coreJS/publishLocal uniJS/publishLocal
  export SBT_OPTS="-Duni.version=<the version it printed>"
  ```

## Run

```bash
pnpm install

# Electron
pnpm dev            # compiles Scala.js, starts the renderer dev server, opens the window

# Browser
pnpm dev:web        # http://localhost:5173, with ChatApi on 127.0.0.1:5174 behind a Vite proxy
pnpm build:web      # out/web/public (static UI) and out/web/server/server.js
pnpm start:web      # serves both on http://127.0.0.1:8080 (set PORT to change it)
```

In `dev:web`, renderer changes reload on save; restart the command after changing `server/` code.

## Package the desktop app

```bash
pnpm package        # electron-vite build, then electron-builder → dist/
```

See [App size](../../docs/http/electron.md#app-size) for how the packaging config keeps the bundle
small.

## Notes

- The web server binds to `127.0.0.1` and has no authentication. Add both before you expose it
  beyond your machine.
- Conversations live in memory and reset when the process exits.
- The Electron window uses the standard OS frame. For a frameless window, set `titleBarStyle` in
  `src/main/index.js` and mark the header as a drag region.
