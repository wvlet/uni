# UI gallery: interactive Rx examples

## Goals

- Readers see how Rx streams behave, not just read about it: each event a stream emits, when, and what ends it.
- Show the current R3 event model (since #1074–#1079): `OnError` is resumable in `subscribe`, `OnCompletion(result)` always ends a stream.
- Keep every example small, copyable, and identical to the code that runs, like the uni-ui pages.

## Background

- The gallery already runs each example from its `// #region` block and shows that block as code; `SourceTag` chips only appear for uni-ui components, so plain Rx code still works.
- Behaviour checked on the current main with a probe test (JVM, `ManualTimeProvider`, events read with `RxRunner.runContinuously`):
  - A throwing `map`: `10, 20, OnError, 40, 50` — the stream keeps going.
  - `subscribe(f)` gets values only: its handler rethrows an `OnError`, so the error escapes to whoever called `:=` (a click handler). A uni-dom binding instead clears its section, logs a warning, and shows the next value.
  - `setException`: `1, 2, OnCompletion(Failure)`; later `:=` emits nothing. `recover` after it emits its value, then a successful completion.
  - `recover` / `recoverWith` after a throwing `map` (continuous): the replacement value, then later values continue.
  - `flatMap` over `Rx.delay`: a new input cancels the pending inner stream (`a` → `ab` gives only `result(ab)`; `onCancel` fires for `a`).
  - A failed inner stream ends `flatMap` with a failure; `recover` inside the inner stream keeps it going.
  - `throttleFirst` passes the first value, drops the rest of the window; `throttleLast` emits the latest at the window end; `Rx.interval` emits the elapsed ms per tick; `timeout` covers only the first event.
  - `zip` pairs by position, `join` emits on any change; `RxVar` skips a `:=` of an equal value; `take` doesn't count an `OnError`.
  - `RxTracker.trace` lists a `subscribe` until it is cancelled.
- Uni has no `debounce` or `distinct` operator, and adding them is out of scope; the examples use what exists (`throttleFirst`, `throttleLast`/`sample`, `interval`, `delay`, `timeout`; `RxVar`'s built-in equality skip).

## Design

Lane: Standard (new sidebar group and pages); the launch prompt carries the direction and approves auto-merge once a quick-review pass has its findings fixed.

- A new **Rx** group in the sidebar and rail, with five topic pages:
  - **Variables & derived values** — `map`/`filter`/`scan` chain with a live readout per stage; `join` vs `zip`; an equal `:=` that emits nothing vs `forceSet`.
  - **Time operators** — `throttleFirst`, `throttleLast`, `Rx.interval` with `take`, `Rx.delay`, and `timeout` on a slow request, driven by clicks.
  - **Async & flatMap** — a search box whose `flatMap` starts a fake request per keystroke and cancels the previous one (shown with `onCancel`); `Rx.future` requests where one failure ends the outer stream unless `recover` sits inside the inner one.
  - **Errors** — a throwing `map` that resumes; `setException` that ends (with a Restart button, since an ended var stays ended); `recover` and `recoverWith`; the same error seen by a DOM binding, by `subscribe` (it escapes `:=`, caught and shown), and by the timeline.
  - **Lifecycle** — subscribe / cancel buttons with a per-example `RxTracker.trace` and a readout refreshed after each action; `take(n)` and `stop()` completing a stream.
- A reusable **timeline** (marble) panel: `Timeline(lane("input", rx), lane("throttleFirst(500)", rx.throttleFirst(500)))`. Each lane subscribes with `RxRunner.runContinuously` while mounted and draws `OnNext` values, `OnError` (✕, the lane continues), and `OnCompletion` (a bar, red when failed) on a shared scrolling time axis. It lives in the gallery shell and the story code calls it, marked by a comment as the gallery's helper: the Rx lines above it are the part to copy. A card-level timeline would still need the story to name its streams, so it would not make the code shorter.
- Topic pages reuse `ComponentPage` with `topic = true` and no API; `PageView` changes to skip the Parts, API, and Accessibility sections (and their TOC links) for them, and `GalleryTest` / the README require an API only on component pages.
- `PageGroup.docs` becomes a path under `/uni/` (`dom/controls`, `rx/operators`), and the Rx group gets its own rail icon.
- Docs: embed a few examples (`<UiGallery story=... embed />`) in `docs/rx/operators.md` next to the time operators, error handling, and leaked-subscription sections, and correct any text there that still describes pre-R3 behaviour.
- Tests in `GalleryTest`: every Rx example mounts; the timeline draws a marble per event, an error marble without ending the lane, and a completion bar; flatMap cancellation and the tracker count behave as shown.

## Alternatives and Why Not?

- Driving the time examples with `ManualTimeProvider` and a "step" button: deterministic, but readers want real timing when they click; tests use the manual provider instead.
- A separate Rx playground app: duplicates the gallery shell (code panel, embed, theming) for no gain.
