# Cancel an RxFiber before its computation has started

## Goals

- `RxFiber.cancel` must stop the computation even if it arrives before the scheduler runs the fiber's task, or while `RxRunner.runOnce` is still starting it. Today both cases leave the computation running, and a waiter it registered (e.g. `RxSemaphore.acquire` in a losing `RxParallel.race` branch) stays registered — the gap #768 left open.

## Background

- `RxFiber.start` schedules `runOnce(rx)` and then calls `setCancelable` with the returned `Cancelable`. `cancel` calls whatever `cancelable` holds at that moment.
- `cancelable` is a plain `var` starting at `Cancelable.empty`. A cancel before `setCancelable` cancels nothing, and nothing cancels the run when it is set later. The write is also not safely published across threads.

## Design

Lane: **Express** — a fix with a clear diagnosis inside `RxFiber`, no public API change, no always-gate area. Per AGENTS.md, merging still asks.

- Hold the run's `Cancelable` in an `AtomicReference`, starting at `null` (not started).
- `cancel` swaps in a sentinel and cancels the previous value if any.
- `setCancelable` does a CAS from `null`; if it fails, the sentinel is there, so it cancels the new run immediately.
- The scheduled task skips `runOnce` when the fiber is already cancelled.
- Tests (cross-platform, with a manual test scheduler): cancel before the task runs leaves no waiter and does not run the computation; cancel while the run is starting removes the waiter it registered.
