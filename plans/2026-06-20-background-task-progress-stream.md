# BackgroundTask follow-ups — Rx progress stream + ADR

## Context

`BackgroundTask` landed in #588 (`wvlet.uni.control`, issue #552): a plain-block worker with
cooperative cancel, an `onCancel` hook, a **poll** progress snapshot (`progress: Option[P]`), and
`await`/`poll`/`cancel`. Two follow-ups were noted in its plan and are now in scope:

1. **A push-based `Rx[P]` progress stream** — reactive consumers (a live CLI progress bar, a UI) want
   to *react* to each progress update rather than poll. The poll snapshot stays; this adds an
   observable on top.
2. **An ADR** — #588 made non-obvious cross-platform decisions (Node `worker_threads` can't host an
   arbitrary Scala closure → JS runs the body inline; cooperative-only cancel; catch-`Throwable` +
   `finally`-signal so `await` never hangs; drain hooks on completion). These are exactly the
   "would I wish this existed if I walked in cold" decisions the workflow says to capture.

Outcome: one small PR adding `progressStream` + the ADR.

## Design

### 1. `progressStream: Rx[P]` on `BackgroundTask`

Add to the trait in `uni/src/main/scala/wvlet/uni/control/BackgroundTask.scala`:

```scala
/** A push stream of progress updates, completing when the task finishes.
  * JVM/Native: live. JS: the body runs inline (see start), so a subscriber attached afterwards
  * sees only the final reported value (RxVar replays current), not a live feed. */
def progressStream: Rx[P]
```

Implementation in `BackgroundTaskImpl`, reusing `wvlet.uni.rx.{Rx, RxVar}` (uni-core):

- Add `private val progressVar = RxVar[Option[P]](None)` as the **notification channel only**.
- **Keep `progressRef: AtomicReference[Option[P]]` as the source of truth for `progress`** — critical:
  `RxVar.get` reads a non-`@volatile` `var` outside any lock (`RxVar.scala:32`) and `update` writes
  `currentValue` outside the `synchronized` block (`RxVar.scala:64-68`), so it gives **no
  cross-thread visibility guarantee**. The poll snapshot must stay on the atomic.
- `reportProgress(p)` updates both: `progressRef.set(Some(p))` then `progressVar.set(Some(p))`.
- `progressStream = progressVar.filter(_.isDefined).map(_.get)` — the same Option-filtering bridge
  `Rx.future` uses (`Rx.scala` `futureToRx`).
- In `launch`'s `finally`, call `progressVar.stop()` to complete the stream when the task ends (next
  to the existing hook-drain + `gate.signal()`). `RxVar.propagateEvent` is `synchronized`, so driving
  it from the worker thread while a caller subscribes is safe.

No new platform files. `control` → `rx` is a fine dependency (no cycle; `rx` is in uni-core).

### 2. ADR — `adr/2026-06-20-background-task.md`

Capture, with the #588 merge SHA `2a44dfc1`:
- **Context/decision**: the primitive, package choice (`control`, the non-Rx twin of `RxFiber`),
  plain-block body + `TaskContext` (cooperative cancel + progress + `onCancel` hook), shared state
  machine + per-platform worker/gate (`BackgroundTaskCompat`: JVM/Native daemon thread + latch; JS
  inline + no-op gate).
- **Non-obvious points a future reader would reverse-engineer**:
  - Node `worker_threads` run a separate JS realm (can't host an arbitrary Scala closure — links to
    `adr/2026-05-14-nodejs-sync-http.md`) and Scala.js is single-threaded ⇒ **JS runs the body inline
    during `start()`**: identical API, no concurrency, `cancel()` post-completion is a no-op.
  - **Cooperative cancel only** (no forced interrupt); the `onCancel` hook is the escape hatch for an
    in-flight FFI call (e.g. `duckdb_interrupt`).
  - `launch` catches **`Throwable`** (not just `NonFatal`) and signals the gate in `finally`, so a
    fatal error / `InterruptedException` becomes a `Failure` rather than hanging `await` — recorded,
    not rethrown, so the JS inline path doesn't make `start` throw.
  - Hooks are **drained on completion** so a later `cancel()` can't fire them against an unrelated
    operation; `onCancel` after drain runs immediately only if `cancelled`.
  - **`RxVar.get` is not cross-thread-visibility-safe** → the poll snapshot uses `AtomicReference`,
    the `RxVar` is only the push channel.
- **Consequences** + link the ADR from `CLAUDE.md` (the "Architecture decisions" list).

## Files

| Action | Path |
|---|---|
| Edit | `uni/src/main/scala/wvlet/uni/control/BackgroundTask.scala` (add `progressStream` + `RxVar` channel) |
| Add  | `adr/2026-06-20-background-task.md` |
| Edit | `CLAUDE.md` (link the new ADR) |
| Edit | `uni/.jvm/src/test/.../control/BackgroundTaskConcurrencyTest.scala` (+ `.native` copy) — stream test |

## Verification

- `./sbt scalafmtAll` then `uniJVM/test uniJS/test uniNative/test`.
- **Stream test (JVM + Native)**: start a task whose body reports `1,2,3` (with small sleeps) then
  returns; `RxRunner.run(task.progressStream){ … }` collects emissions into a buffer guarded by a
  `CountDownLatch`; assert the buffer ends with `3` (and contains the reported values) and that the
  stream completes (`OnCompletion`) after the task finishes. Not added cross-platform: on JS the
  inline body completes before a subscriber can attach, so there is nothing live to observe.
- Existing `BackgroundTaskTest` / `BackgroundTaskConcurrencyTest` stay green (additive change).

## Risks / notes

- **JS**: `progressStream` is best-effort (final value only) — documented on the method; the poll
  snapshot remains the JS-friendly path.
- Every task now allocates one `RxVar` even if the stream is unused — negligible; keeps the API
  uniform (no lazy/opt-in complexity).
- Out of scope (later): `Thread.interrupt()` on cancel, worker pooling, timeouts.
