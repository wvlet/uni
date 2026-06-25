# Electron desktop app support for Uni

Date: 2026-06-24

## Goal

Enable Uni to build a desktop app (Electron) with proper IPC support, a build
pipeline using Scala.js + Vite + packaging (electron-builder), and ship an
example Electron project as a reference.

## Design

Electron has three runtimes:

- **Main process** (Node.js): owns windows, the filesystem, and the backend.
  Hosts the RPC service implementations.
- **Preload** (privileged bridge): a tiny hand-written JS that uses
  `contextBridge` + `ipcRenderer.invoke` to expose a single safe function to the
  renderer. Context isolation stays ON.
- **Renderer** (Chromium): the UI. No Node access; talks to the main process
  only through the preload bridge.

Uni already has a transport-agnostic RPC stack:

- `RPCRouter.of[T](impl)` builds routes (`POST /{service}/{method}`) from a
  trait via `Surface`.
- `RPCClient.build(...)` / generated `*Client` stubs send the same JSON envelope
  (`{"request": {...}}`) over an `HttpSyncClient` / `HttpAsyncClient`.
- Clients send through a pluggable `HttpChannel` / `HttpAsyncChannel`
  (`Http.setDefaultChannelFactory`).

So the cleanest integration is a **custom async channel that rides over Electron
IPC** plus a **main-process dispatcher** — the existing RPC clients and routers
work unchanged.

### Library changes (`uni`)

1. `wvlet.uni.http.rpc.RPCDispatcher` (shared/cross-platform): extract the
   route-map + dispatch logic from netty's `RPCHandler` into a reusable class
   taking `Seq[RPCRouter]` and returning `Rx[Response]`. `RPCHandler` delegates
   to it (single source of truth, now reusable on JS).

2. `wvlet.uni.electron` (JS-only, `uni/.js`):
   - `ElectronIPC` — the wire contract: channel name `"uni-rpc"`, and
     `Request`↔JS-payload / `Response`↔JS-payload marshaling (plain
     `{method, uri, headers, body}` objects, structured-clone safe).
   - `ElectronRendererChannel` (`HttpAsyncChannel`) + `ElectronChannelFactory`
     (`HttpChannelFactory`) — renderer side. Calls a bridge function
     (`window.uniRPC.request(payload): Promise`).
   - `ElectronRenderer.install(bridge?)` — wires
     `Http.setDefaultChannelFactory`.
   - `ElectronRPCServer.serve(ipcMain, routers*)` — main side. Registers
     `ipcMain.handle("uni-rpc", ...)`, dispatches via `RPCDispatcher`, converts
     `Rx[Response]` → `js.Promise`.

   Electron objects (`ipcMain`, the bridge) are **handed in as values**, never
   `require("electron")`-ed from Scala — avoids Scala.js linker/DCE coupling and
   keeps the code build-tool agnostic (same handoff pattern as the
   cross-platform init ADR).

3. JS tests with a fake bridge + fake `ipcMain` exercising a full round-trip.

### Example project (`examples/electron-app`)

Full-stack Scala: shared API, Scala.js main process + Scala.js renderer, a tiny
preload bridge, Vite for the renderer, electron-builder for packaging.

```
examples/electron-app/
  build.sbt                # api (JS) + main (Scala.js) + renderer (Scala.js)
  project/{build.properties,plugins.sbt}
  api/      .../CounterApi.scala       # shared RPC trait + models
  main/     .../MainProcess.scala       # service impl + ElectronRPCServer.serve
  renderer/ .../RendererApp.scala       # UI + RPC client over IPC
  electron/main.cjs, electron/preload.cjs
  index.html, vite.config.mjs
  package.json             # vite, electron, electron-builder + scripts
  README.md
```

### Docs

- `docs/guide/electron.md` — how the transport works + how to build/package.
- ADR `adr/2026-06-24-electron-ipc-transport.md`.

## Verification

- `./sbt uniJS/compile` and the new JS tests under Node.
- `./sbt uni-netty/compile` (RPCHandler refactor).
- `scalafmtAll`.
- Example: author + typecheck Scala.js compile; document the
  pnpm/electron-builder run (heavy toolchain, may not run in CI sandbox).
