# 2026-05-15: Value-based handoff for cross-platform `Compat` init

PR: https://github.com/wvlet/uni/pull/549 (commits 687296f → 2bff563 → c15537e
attempted the callback approach; 86c3104 rewrote it as a value-based handoff after
CI revealed the Scala.js gap)

## Context

uni has several `wvlet.uni.<area>` shared modules whose runtime behavior depends on
platform-specific objects under `.jvm` / `.js` / `.native`. The pattern that's emerged
is a `<Area>Compat` object — a platform-specific singleton extending a shared
`<Area>CompatApi` trait, used by the shared code to access platform behavior. `HttpCompat`
is the canonical example today.

Some of these platform-specific facts have to be wired into the shared module *at init
time*, before any caller uses the public API. The motivating case here was
`Http.defaultChannelFactory`: cross-platform code (e.g., `wvlet/wvlet`'s Trino client)
should be able to call `Http.client.newSyncClient` directly, without per-platform
boilerplate that does `Http.setDefaultChannelFactory(<platform factory>)`. The
boilerplate it was replacing existed precisely because the shared `Http` object had no
way to learn which platform factory to use without help.

Two natural-looking patterns exist for "init the shared object from the platform":

**A. Callback.** Platform `HttpCompat` runs `Http.setDefaultChannelFactory(<factory>)` as
a class-init side-effect; shared `Http` touches `HttpCompat` somewhere to force the
class init.

**B. Value handoff.** Platform `HttpCompat` exposes the factory as a `val
defaultHttpChannelFactory: HttpChannelFactory`; shared `Http.defaultChannelFactory`
initializes itself by reading that val.

We tried A first. It failed on Scala.js. This ADR captures why.

## Decision

**Use the value-handoff pattern (B). Avoid the callback pattern (A) for cross-platform
init.**

Shape:

```scala
// shared HttpCompatApi
private[http] trait HttpCompatApi:
  def defaultHttpChannelFactory: HttpChannelFactory
  ...

// per-platform HttpCompat (.jvm / .js / .native)
private[http] object HttpCompat extends HttpCompatApi:
  override val defaultHttpChannelFactory: HttpChannelFactory = JVMHttpChannelFactory // / JS / Native
  ...

// shared Http
object Http:
  @volatile private[http] var defaultChannelFactory: HttpChannelFactory =
    HttpCompat.defaultHttpChannelFactory
  def setDefaultChannelFactory(factory: HttpChannelFactory): Unit = defaultChannelFactory = factory
```

The `var` initializer is a single expression that *reads* `HttpCompat`'s val. That read
forces `HttpCompat`'s init, and the val's value flows directly into the shared field.
No callback from `HttpCompat` into `Http.setDefaultChannelFactory`, no init cycle, no
ordering question between two objects.

## Why not the callback pattern

Three things compound on Scala.js to make pattern A fragile:

1. **Linker DCE on bare object references.** The first attempt (commit 687296f) was
   `private val _httpCompatInit = HttpCompat` inside `Http`. On the JVM this forces
   `HttpCompat.<clinit>`; on Scala.js the linker may treat the `val` as unused and drop
   it, in which case `HttpCompat`'s class-init never runs and the factory stays at the
   sentinel `NoOpChannelFactory`. Local `testOnly` happened to pass; full CI exposed it.

2. **Method-call workaround creates an init cycle.** Promoting the trigger to
   `HttpCompat.installDefaultChannelFactory()` (commit during PR, not landed) made the
   linker keep the call but introduced a cycle: `Http.<clinit>` calls
   `HttpCompat.<clinit>`, which calls `Http.setDefaultChannelFactory`, which writes back
   into `Http.defaultChannelFactory`. The JVM resolves this fine — `Http` finishes its
   field assignments before transferring control, the callback sees a fully-initialized
   `defaultChannelFactory` var, and mutates it. Scala.js's lazy module initialization
   sequenced these so that `Http.defaultChannelFactory` ended up at its sentinel default
   anyway. The exact mechanism wasn't fully diagnosed; the fix was to remove the cycle.

3. **No `@volatile` story on cross-platform `var`s.** If a platform-specific callback
   writes into a shared `var`, you depend on whatever memory-model semantics each platform
   provides. The value-handoff version writes once, during the shared object's own
   `<clinit>` — the standard "publish via a final-class-init" pattern, no cross-thread
   reasoning needed for the default value (the `@volatile` is then only about later
   `setDefaultChannelFactory` overrides, which is a smaller scope).

The diagnostic loop was: green local JVM/JS/Native `testOnly` runs, then red CI on
Scala.js because the full test suite ran the asserting test on a path the linker hadn't
preserved. A diff between local and CI showed both ran the same compilation, but CI's
Scala.js test runner saw the linker-DCE'd version.

## Consequences

- **Future cross-platform init follows this shape.** When a new shared module needs to
  learn a platform-specific value at startup, expose it as `def`/`val` on the
  `<Area>CompatApi` trait and let the shared object read it during its own init. Don't
  rely on the platform object calling back into the shared one.
- **Platform `Compat` objects can be pure value providers.** They don't need class-init
  side-effects. That simplifies their bodies and makes them easier to read.
- **`@volatile` covers later mutations only.** The initial value is published via
  shared-object class-init, which is already a JVM happens-before edge. `@volatile` is
  there so callers like `wvlet-server` that call `setDefaultChannelFactory` at runtime
  see their writes across threads.
- **Test gotcha worth remembering.** uni-test's `shouldNotBe` falls through a
  Scala.js-specific `js.Object` matcher that compares values by `JSON.stringify`.
  Field-less Scala singletons all stringify to `{}` and compare equal, so use
  `shouldNotBeTheSameInstanceAs` / `shouldBeTheSameInstanceAs` for reference identity.
  This bit the test in `HttpDefaultChannelFactoryTest.scala`.

## Worked example

The commits in PR #549 walk the journey:

- `687296f` — first cut: `private val _httpCompatInit = HttpCompat`. CI Scala.js red.
- `2bff563` — added an init-ordering comment, no behavior change. Still red.
- `c15537e` — refined the test assertion (cleanup); the underlying bug stayed.
- `86c3104` — restructured to the value-handoff pattern. CI green on all three
  platforms.
- `fefb76f` — added `@volatile` for cross-thread visibility of later overrides.

If a future change reaches for "have `<Area>Compat`'s class-init write to the shared
object," that's the pattern to avoid. Make the shared object read instead.
