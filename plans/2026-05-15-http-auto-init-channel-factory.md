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

Have `HttpCompat` **provide** the platform default factory as a value, and have `Http`
read it directly when initializing `Http.defaultChannelFactory`. No mutating callback,
no side-effect ordering between objects — a single read evaluates the val.

```scala
// HttpCompatApi.scala (shared)
private[http] trait HttpCompatApi:
  def defaultHttpChannelFactory: HttpChannelFactory
  ...

// HttpCompat.scala (.jvm / .js / .native — each platform)
private[http] object HttpCompat extends HttpCompatApi:
  override val defaultHttpChannelFactory: HttpChannelFactory = JVMHttpChannelFactory  // (or JS / Native)
  ...

// Http.scala (shared)
object Http:
  private[http] var defaultChannelFactory: HttpChannelFactory = HttpCompat.defaultHttpChannelFactory
  def setDefaultChannelFactory(factory: HttpChannelFactory): Unit = defaultChannelFactory = factory
```

Each platform's `HttpCompat` object exposes the right factory as a `val`. `Http`'s
`defaultChannelFactory` initializer reads it — that read forces `HttpCompat`'s
initialization, and the val's value flows straight into `Http`. No callback into
`Http.setDefaultChannelFactory`, no class-init cycle to reason about.

### Why not the callback approach

The first cut had `HttpCompat`'s class-init body call back into
`Http.setDefaultChannelFactory(<factory>)`, and `Http`'s init touched `HttpCompat` to
trigger that side-effect. That works on the JVM but is fragile under Scala.js:

1. A bare object reference (`val _ = HttpCompat`) is not a reliable init trigger — the
   Scala.js linker may drop the unused value, in which case `HttpCompat`'s class-init
   never runs.
2. Even with an explicit method call (`HttpCompat.installDefaultChannelFactory()`), the
   call sequence creates an `Http` ↔ `HttpCompat` init cycle. The JVM resolves it by
   completing `Http`'s field assignments before calling out, then letting the callback
   mutate the now-initialized var. Scala.js's lazy module initialization handled the
   sequence differently and left `Http.defaultChannelFactory` at its sentinel default.

The value-based handoff sidesteps both: `Http.defaultChannelFactory` is initialized in a
single expression that reads `HttpCompat.defaultHttpChannelFactory`. No callback, no
cycle, identical behavior across JVM / JS / Native.

## Downstream impact

After this lands, code like `TrinoCompat` (wvlet#1708) can be deleted:

- Shared code calls `Http.client.newSyncClient` directly.
- No `.jvm` / `.js` / `.native` boilerplate.
- No mix-in trait, no `installHttpFactory()` ceremony.

Existing callers that *do* call `Http.setDefaultChannelFactory` explicitly (e.g.,
`wvlet-server`) keep working — the explicit call just reassigns the same `var`.

## Files

- `uni/src/main/scala/wvlet/uni/http/HttpCompatApi.scala` — added abstract
  `defaultHttpChannelFactory: HttpChannelFactory`.
- `uni/.jvm/src/main/scala/wvlet/uni/http/HttpCompat.scala` — override with
  `JVMHttpChannelFactory`; removed the class-init `Http.setDefaultChannelFactory(...)`
  call (now redundant — `Http` reads the val).
- `uni/.js/src/main/scala/wvlet/uni/http/HttpCompat.scala` — same shape, `JSHttpChannelFactory`.
- `uni/.native/src/main/scala/wvlet/uni/http/HttpCompat.scala` — same shape, `NativeHttpChannelFactory`.
- `uni/src/main/scala/wvlet/uni/http/Http.scala` — `defaultChannelFactory` now initializes
  from `HttpCompat.defaultHttpChannelFactory` (was `NoOpChannelFactory`).
- `uni/src/test/scala/wvlet/uni/http/HttpDefaultChannelFactoryTest.scala` — shared test
  asserting `Http.defaultChannelFactory` is not the NoOp sentinel after merely touching
  `Http`. Uses `shouldNotBeTheSameInstanceAs` for reference identity — see
  "Scala.js framework quirk" below.

## Risks and mitigations

- **Browser bundlers**: `HttpCompat` on `.js` now references `JSHttpChannelFactory` from
  its `defaultHttpChannelFactory` val. Loading `wvlet.uni.http.Http` therefore eagerly
  loads `JSHttpChannelFactory`, which is already what the previous callback flow did.
  `JSHttpChannelFactory.newChannel` defers its `worker_threads` load until a sync HTTP
  call (`process.getBuiltinModule` is not invoked at class-init — see
  `adr/2026-05-14-nodejs-sync-http.md`), so this change does not regress browser
  bundling.

## Scala.js framework quirk

`uni-test`'s `shouldNotBe` falls through `compat.platformSpecificMatcher` on Scala.js,
which compares any two `js.Object` values via `JSON.stringify`. Field-less singleton
objects (`JSHttpChannelFactory`, `NoOpChannelFactory`, etc.) both serialize to `{}` and
compare equal — `shouldNotBe` reports them as the same value even when they're different
instances. Tests that need to distinguish singleton identities must use
`shouldNotBeTheSameInstanceAs` / `shouldBeTheSameInstanceAs` (reference equality), not
the deep-equality matchers. Worth fixing in uni-test eventually, but out of scope here.

## Test plan

- [x] `uniJVM/test` — 1612/1612 passing
- [x] `uniJS/testOnly wvlet.uni.http.*` — 300/300 passing (full `uniJS/test` has a
      pre-existing JS-side `FileSystemTest.delete directory recursively` flake unrelated
      to this PR)
- [x] `uniNative/testOnly wvlet.uni.http.HttpDefaultChannelFactoryTest` — passing
- [x] CI passes (Scala.js, Scala 3, Scala Native)
