# Rx time/frame providers and subscription tracker (ideas from R3)

Goal: adopt two R3 ideas that need no change to the Rx event model.

- A2: `RxTimeProvider` + `ManualTimeProvider` for time operators; `RxFrameProvider` (rAF on JS)
  + `ManualFrameProvider`; new `Rx.everyFrame`, `Rx.nextFrame`, `sampleFrame`.
- A1: `RxTracker` for live subscriptions (thread-scoped `trace`, process-wide `startGlobal`),
  `assertNoRxLeaks` in uni-test.

Out of scope: R3's `OnErrorResume` / terminal `OnCompleted(Result)` event model (option B). Next
step there: model it in `spec/lean` and replay the cases behind the 2026-10/11 completion
amendments, then decide.

Design decisions are in `adr/2026-11-21-rx-time-provider-and-tracker.md`.
