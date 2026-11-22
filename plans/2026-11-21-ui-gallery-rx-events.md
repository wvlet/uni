# UI gallery: Rx stream events

## Goals

- Readers of docs/rx/events.md see each rule run: the event grammar, OnError vs OnFailure, `run` vs `runContinuously`, and RxVar events.
- Every lane shows the same event sequence as the matching trace on that page.
- Reuse the existing Rx stories where they already show a concept, so the gallery doesn't show the same demo twice.

## Background

- PR 1113 made a stream's end `OnCompletion` or `OnFailure`, and `RxRunner.run` delivers the first error as `OnFailure`. The Timeline already draws all four events and has `Lane.run` for `RxRunner.run`.
- Stories that already cover parts of this:
  - `rx-errors/resume`: a throwing map, `recover`, and a `run` lane next to a continuous lane (goal 3).
  - `rx-errors/terminal`: `setException`, `recover`, and `recoverWith`.
  - `rx-lifecycle/end`: `stop()` and `take(3)`.
  - `rx-values/equal`: the equal-value skip.
  - `rx-async/future`: a failed `Rx.future` inside a `flatMap`.
- The gallery links each page to its group's docs page (`rx/operators` for the Rx group). No page can point to `rx/events` yet.

## Design

Lane: Standard (a new gallery page). The launch prompt sets the direction and the stories, so no design card. The prototype gate decides, and merging needs the user's explicit OK.

- A new **Stream events** page (`rx-events`) in the Rx group, placed before Errors, with three stories:
  - **`rx-events/traces`: the event grammar.** One lane per stream from the docs' Event Traces table: `fromSeq`, `empty`, `single`, `exception`, `map(boom)`, `filter`, `take(2)`, `lastOption`, `concat`, `exception.concat`, and `materialize`. The `exception.concat` lane ends with OnFailure, and its `3` never arrives, which shows "nothing after an end". The test checks the raw events, because the Timeline itself drops anything after an end. A **Replay** button runs the streams again.
  - **`rx-events/kinds`: OnError vs OnFailure.** The docs' `parsed` (`"1","x","3"` mapped with `toInt`) and `failed` (`Rx.exception`), plus a failed `Rx.future` that settles after 500 ms. Each has a lane with `recover` under it. Replay re-runs them.
    - `parsed` overlaps `rx-errors/resume` on purpose: here it sits next to the failed sources, so the two kinds of error can be compared. `RxVar.setException` stays in `rx-errors/terminal` and `variable`, and the docs link to it.
    - The docs' trace table gains a row for a failed `Rx.future`: `OnFailure(e)`.
  - **`rx-events/variable`: RxVar events.** Buttons `counter := counter.get + 1`, `counter := counter.get` (sends nothing), `stop()`, `setException`, and Restart. A **subscribe again** button starts a new subscription through `flatMap`, and that lane gets the current value right away. `stop()` also stops the button's variable, so the `flatMap` lane completes as well.
- Goal 3 reuses `rx-errors/resume` instead of adding a new story. Its lanes get the entry-point names (`checked, with runContinuously` and `checked, with run`), so the side-by-side view reads like the docs' entry-point table.
- `ComponentPage` gets an optional `docs` path. The page's "docs" link then opens `rx/events` instead of the group's `rx/operators`.
- Docs embeds in docs/rx/events.md:
  - `traces` under Event Traces.
  - `kinds` under Two Kinds of Errors, in place of `resume`.
  - `variable` under Variables.
  - `resume` under Ways to Run a Stream.
- Tests in `GalleryTest`: each new story's lanes match the docs traces; also the updated Rx page list, the renamed resume lanes, and the page's docs link.
- Verify in the running gallery with Playwright over CDP.

## Alternatives and Why Not?

- New stories on the Errors page: the grammar and RxVar stories are not about errors, and docs/rx/events.md is a page of its own.
- A new side-by-side `run` story: it would repeat `rx-errors/resume`, which already shows exactly this.
- Pacing the trace streams with timers so marbles arrive one by one: the lanes would no longer run the streams from the docs. Marbles of synchronous events already sit side by side in order.
