# wvlet.uni.plugin: extension-point model instead of hardcoded contribution kinds

Date: 2026-07-06

## Context

The plugin layer of the multi-layer testing initiative (PR #618) initially shipped a
VSCode-style plugin model whose `PluginContext` hardcoded exactly two contribution kinds:
string-keyed commands (`registerCommand`) and `registerRpcRouter(router: RPCRouter)`. That
made `wvlet.uni.plugin` — a top-level namespace alongside `design`, `rx`, `surface` —
depend on `wvlet.uni.http.rpc`, and made the core trait closed for extension: every future
contribution kind (views, menus, CLI commands, `Design` bindings) would require editing
`PluginContext`.

## Decision

Invert the model around **typed extension points**:

- `ExtensionPoint[A](name, keyOf)` — a typed contribution slot, compared by identity (define
  points as shared `val`s/`object`s). `keyOf = Some(f)` makes the point *keyed*: a duplicate
  key, within or across plugins, is rejected at activation time so conflicts surface in tests
  rather than at runtime. `ExtensionPoint.keyed(name)(f)` is the constructor for that.
- `PluginContext` shrinks to `contribute(point)(value)` + `onDeactivate(hook)`.
- `PluginHost` owns a generic `ExtensionPoint -> contributions` registry, queried with
  `contributions(point)` / `contribution(point, key)`.
- Each layer defines its points **next to the contributed types**, so dependency arrows point
  *into* `wvlet.uni.plugin`, never out of it:
  - `wvlet.uni.plugin.Command` — `Command.point` (keyed by command id) plus
    `registerCommand` / `executeCommand` / `commandIds` / `hasCommand` extension methods.
  - `wvlet.uni.http.rpc.RPCPlugin` — `routerPoint` plus `registerRpcRouter` /
    `rpcRouters` / `rpcDispatcher` extension methods. `PluginHost.dispatcher` moved here.

The ergonomic surface is unchanged for plugin authors (`ctx.registerCommand(...)`,
`ctx.registerRpcRouter(...)`) — they are extension methods now, imported via
`import wvlet.uni.plugin.Command.*` and `import wvlet.uni.http.rpc.RPCPlugin.*`.

## Consequences

- `wvlet.uni.plugin` is dependency-free within uni (only stdlib), justifying the top-level
  namespace: any uni app — CLI, HTTP server, Electron — can define its own points without
  pulling in RPC concepts.
- New contribution kinds are added by *defining a point*, not editing the core. A natural
  follow-up is a `Design` extension point so plugins contribute DI bindings.
- `PluginTestHost` is unchanged: tests still activate against a fresh host through the real
  activation path and run JVM-only; assertions go through `contributions(point)` or the
  point-specific extension methods.
- Trade-off: point identity (not name) is the registry key, so a point must be a shared
  singleton; two `ExtensionPoint("x")` instances are distinct slots. This keeps lookups typed
  without a global name registry.
