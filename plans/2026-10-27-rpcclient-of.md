# RPCClient.of[T]

## Goals

- Let users build a typed RPC client from the service type alone: `RPCClient.of[CounterApi]` instead of `RPCClient.build(Surface.of[CounterApi], Surface.methodsOf[CounterApi])`.
- Make the client side mirror the server side, which already has `RPCRouter.of[T](impl)`.

## Background

- `RPCClient.build(serviceSurface, methods)` is a plain (non-inline) method. It needs compile-time metadata, so every caller writes both `Surface.of[T]` and `Surface.methodsOf[T]` (both inline macros). Repeating `T` twice is boilerplate and allows mismatched arguments.
- `RPCRouter.of[T]` already solves this on the server with an `inline def` that expands both macros at the call site. No new macro is needed: an `inline def` that calls the existing `Surface` macros is enough, because inlining makes `T` concrete at the call site.
- The pattern appears in docs (`docs/http/electron*.md`, `docs/core/surface.md`), the `uni` skill (`references/rpc.md`, `references/electron.md`), doc-snippet stubs, examples (`rest-server`, `electron-app`), tests, and in source emitted by `RPCClientGenerator`.

## Design

Lane: **Express** — a small additive API (one inline wrapper) mirroring `RPCRouter.of`, with no behaviour change and no always-gate area.

- Add to `object RPCClient`:
  ```scala
  inline def of[T]: RPCClient = build(Surface.of[T], Surface.methodsOf[T])
  ```
- Keep `build(serviceSurface, methods)` public: it is the non-inline entry point for callers that already hold `Surface` values (e.g. generic code), and removing it would break existing users.
- Switch call sites to `RPCClient.of[T]`: docs, skill references, snippet stubs, examples, tests (except one test that keeps covering `build`), and the `RPCClientGenerator` output (plus its test expectation; the `Surface` import goes away from generated code).
- Update `docs/guide/for-ai-agents.md` if it shows the client construction, and bump the `uni` plugin version since the skill snippets change.

## Alternatives and Why Not?

- **Deprecate `build`** — it is still the right API when the `Surface` is computed elsewhere; keep it, just stop leading with it.
- **A dedicated macro (quotes/splices)** — unnecessary; `inline` + existing `Surface` macros already gives a type-only API with no new macro code to maintain across JVM/JS/Native.
- **Name it `apply[T]`** — `RPCClient[CounterApi]` reads like a type application and clashes visually with the class constructor; `of[T]` matches `RPCRouter.of` and `Surface.of`.
