# Http: auto-initialize the platform channel factory

Branch: `feature/http-auto-init-channel-factory`

## Problem

Cross-platform callers of `Http.client.newSyncClient` currently have to register the
platform-specific channel factory themselves, once per platform. This is the boilerplate
that wvlet/wvlet#1708 adds (`TrinoCompat.scala` in `.jvm` / `.js` / `.native`):

```scala
trait TrinoCompat:
  def installHttpFactory(): Unit = Http.setDefaultChannelFactory(JVMHttpChannelFactory)
```

Every downstream library that uses uni's HTTP client cross-platform has to repeat this
shape three times. uni already auto-registers the right factory inside
`HttpCompat.<clinit>`:

```scala
// uni/.jvm/src/main/scala/wvlet/uni/http/HttpCompat.scala
private[http] object HttpCompat extends HttpCompatApi:
  ...
  Http.setDefaultChannelFactory(JVMHttpChannelFactory)
```

…but `HttpCompat` is only referenced from `HttpExceptionClassifier.executionFailureClassifier`,
which only runs on the error path. So if `Http.client.newSyncClient` is the first thing a
caller touches, `HttpCompat` has not loaded yet and `Http.defaultChannelFactory` is still
`NoOpChannelFactory`, which throws `"No HttpChannel implementation available"`.

## Root cause

`HttpCompat` is `private[http] object HttpCompat`. Scala object init is lazy — it runs on
first reference. Today the only place that references it is the exception classifier, so
the factory-registration side-effect is gated on an error path that may never happen
before a real request.

## Fix

Force `HttpCompat` to load whenever `Http` itself loads. Touching the object once from
`Http`'s class-init runs `HttpCompat.<clinit>`, which registers the platform default
channel factory before any caller can call `Http.client.newSyncClient`.

```scala
// uni/src/main/scala/wvlet/uni/http/Http.scala
object Http:
  // Loading HttpCompat registers the platform-specific default channel factory as a
  // side-effect of its class-init. Reference it here so the registration happens before
  // Http.client is ever used — otherwise the first call would resolve NoOpChannelFactory
  // and throw "No HttpChannel implementation available".
  private val _httpCompatInit = HttpCompat
  ...
```

`HttpCompat` is `private[http]` and lives in the same package as `Http`, so the reference
compiles on all three platforms. The line is on the shared (`uni/src/main`) side because
all three platforms ship an `HttpCompat` object at the same fully-qualified name.

## Downstream impact

After this lands, code like `TrinoCompat` (wvlet#1708) can be deleted:

- Shared code calls `Http.client.newSyncClient` directly.
- No `.jvm` / `.js` / `.native` boilerplate.
- No mix-in trait, no `installHttpFactory()` ceremony.

Existing callers that *do* call `Http.setDefaultChannelFactory` explicitly (e.g.,
`wvlet-server`) keep working — the explicit call just reassigns the same `var`.

## Files

- `uni/src/main/scala/wvlet/uni/http/Http.scala` — touch `HttpCompat` from object init.
- `uni/.jvm/src/test/scala/wvlet/uni/http/HttpDefaultChannelFactoryTest.scala` — new
  JVM test asserting that `Http.defaultChannelFactory` resolves to a non-NoOp factory
  immediately, and that `Http.client.newSyncClient` succeeds without any other warm-up.

## Risks and mitigations

- **Browser bundlers**: `HttpCompat` on `.js` references `HttpExceptionClassifier`. It
  does **not** reference `JSHttpChannelFactory` directly — only via the `Http.setDefaultChannelFactory`
  call at init. That call passes the `JSHttpChannelFactory` object reference. Browsers
  that load `wvlet.uni.http.Http` would therefore eagerly load
  `JSHttpChannelFactory`, which in turn does a runtime `process.getBuiltinModule` load
  of `worker_threads` (see `adr/2026-05-14-nodejs-sync-http.md`). That load is already
  bundler-invisible by design, so this change does not regress browser bundling.
- **Init ordering**: `HttpCompat` references `Http.setDefaultChannelFactory`, which is
  a method on `Http`. With this change, `Http.<clinit>` will reference `HttpCompat`,
  which will reference `Http`. On the JVM this is the standard "object cycle" pattern —
  `Http` finishes its static-init enough to expose `setDefaultChannelFactory` before
  the `val _httpCompatInit` line runs, because the `val` is the *last* line of
  `Http.<clinit>`. On Scala.js / Native it's the same story (no separate `<clinit>`
  phase, but field-by-field linearization gives the same answer).

## Test plan

- [x] `coreJVM/test` passes locally
- [x] `coreJS/test` passes locally
- [x] `coreNative/test` passes locally (or compile-only if Native runtime is unavailable)
- [x] New `HttpDefaultChannelFactoryTest` exercises the cold-init path explicitly
