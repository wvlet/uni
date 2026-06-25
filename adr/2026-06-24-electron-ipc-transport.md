# 2026-06-24: Electron IPC transport for Uni RPC

## Context

Uni already has a transport-agnostic RPC stack — `RPCRouter.of[T]` builds routes from a trait via
`Surface`, and `RPCClient` / generated stubs send the JSON envelope `{"request": {...}}` over a
pluggable `HttpChannel`. To let Uni build Electron desktop apps, the renderer (Chromium, no Node
access) needs to call services that live in the main process (Node.js). The natural carrier is
Electron IPC (`ipcRenderer.invoke` / `ipcMain.handle`), not a network socket.

## Decision

Add a Scala.js-only `wvlet.uni.electron` package that rides the **existing** RPC stack over IPC,
plus extract the server dispatch into a transport-neutral helper so both HTTP and IPC reuse it.

- **`RPCDispatcher`** (shared, `uni/src/main/.../http/rpc/`): the route-map + decode + invoke +
  encode logic that used to live inside netty's `RPCHandler`, now a `class RPCDispatcher(Seq[RPCRouter])`
  returning `Rx[Response]`. `RPCHandler` is now a thin `RxHttpHandler` delegating to it. Single
  source of truth; reusable on JS.
- **Renderer** (`ElectronRendererChannel` + `ElectronChannelFactory` + `ElectronRenderer.install`):
  an `HttpAsyncChannel` that marshals a request to a plain object and calls a preload bridge
  (`window.uniRPC.request(payload): Promise`). `install()` wires it via
  `Http.setDefaultChannelFactory`, so existing async RPC clients work unchanged.
- **Main** (`ElectronRPCServer.serve(ipcMain, routers*)`): registers `ipcMain.handle("uni-rpc", …)`,
  dispatches via `RPCDispatcher`, and bridges the `Rx[Response]` to a `js.Promise`.
- **`ElectronIPC`**: the wire contract — channel name `"uni-rpc"`, and `Request`↔`{method,uri,headers,body}`
  / `Response`↔`{status,headers,body}` marshaling using structured-clone-safe plain objects (body is
  always a UTF-8 JSON string, so no typed-array marshaling).

## Non-obvious points a future reader would otherwise reverse-engineer

- **Electron objects are handed in as values, never `require("electron")`-ed from Scala.** `ipcMain`
  and the renderer bridge are parameters. `require("electron")` would not work anyway (`electron`
  isn't a Node *builtin*, so `NodeModules.builtin` can't load it; and ESM Scala.js output has no
  `require`), and value-handoff keeps the code free of Scala.js linker/DCE coupling — same lesson as
  the cross-platform init ADR (`2026-05-15`).
- **The renderer is async-only.** A sandboxed renderer has no synchronous IPC, so
  `ElectronChannelFactory.newChannel` throws and only `newAsyncChannel` is real — mirroring how
  `JSHttpChannelFactory` treats sync HTTP in browsers.
- **`defaultBridge` reads `window` via scalajs-dom, not `js.Dynamic.global`.** Selecting a property
  off the global scope with a *dynamic* name is a Scala.js compile error ("Loading the global scope
  as a value…"). `org.scalajs.dom.window.asInstanceOf[js.Dynamic].selectDynamic(name)` sidesteps the
  global-scope rules.
- **`RPCClient.buildRequest` had a latent bug: scalar params never worked.** It did
  `JSON.parse(weaver.toJson(value))`; for a `String`/`Int` the JSON form is a bare value (`"world"`,
  `42`) which strict `JSON.parse` rejects (it only accepts object/array at top level). No runtime RPC
  test existed (the sbt-test only checks codegen *compiles*), so this surfaced the moment the first
  end-to-end IPC test ran. Fixed to `JSON.parseAny`.
- **`scalajs-java-securerandom` had to become a compile dependency.** Uni's main code reaches
  `java.security.SecureRandom` through `ULID` in the `Weaver` derivation chain, but the dep was
  declared `% Test`. Uni's own JS module only ever *links* under test (where the dep is present), so
  the gap was invisible until a downstream app (the example) tried to link uni's main code and failed
  with "Referring to non-existent class java.security.SecureRandom". Now `compile`-scoped so desktop
  apps link out of the box.
- **Rx→Promise on the main side resolves, never rejects.** `RPCDispatcher` already encodes RPC
  failures as error `Response`s, so the `OnError` branch is defensive only and still *resolves* with
  an `INTERNAL_ERROR` response — the renderer always gets a well-formed payload and the client's
  normal HTTP error handling applies.

## Reference

Full example app (sbt + electron-vite + electron-builder): `examples/electron-app`. Guide:
`docs/http/electron.md`.
