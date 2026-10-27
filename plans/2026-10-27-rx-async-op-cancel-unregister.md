# Unregister Rx waiters on cancel

## Goals

- A cancelled `RxSemaphore.acquire`/`acquireN` or `RxBoundedQueue.take` must not consume a permit or element. Today it does: cancelling the run only stops delivery, so the waiter stays queued and later swallows a permit or element that nobody receives. Under `timeout` or `RxParallel.race` patterns this leaks permits until the semaphore deadlocks, and drops queue elements.
- A cancelled waiting `offer`, `RxDeferred.get`, or `RxFiber.join` should not leave its callback queued (memory held until completion; a cancelled offer would still enqueue its element).
- No public API change: `Rx.AsyncOp` is `private[rx]`.

## Background

- #766 added `private[rx] Rx.AsyncOp(register: (Try[A] => Unit) => Unit, name)` in `uni-core`. `RxRunner` calls `register` with a callback guarded by an `AtomicBoolean`: at most one delivery, none after cancel. The run's `Cancelable` only flips that flag.
- `adr/2026-10-27-rx-async-callback-sources.md` records the leak as a known limitation.
- Cancellation reaches an `AsyncOp` through the run's `Cancelable`: `TimeoutOp` cancels its input on timeout, and `RxParallel.race` cancels losing fibers (`RxFiber.cancel` → the fiber's run `Cancelable`). Both start working for waiters once `AsyncOp` cancellation unregisters.

## Design

Lane: **Express** — a fix with a clear diagnosis inside `wvlet.uni.rx`, no public API change, no always-gate area. The launch prompt withholds merge authorization, so merging still asks.

- Change the private signature to `AsyncOp[A](register: (Try[A] => Boolean) => Cancelable, name)`.
  - The callback returns `true` when the result was delivered to this run, `false` when the run was already cancelled (or already delivered).
  - `register` returns a `Cancelable` that removes the waiter it registered.
- `RxRunner` `case AsyncOp`: cancel sets `done = true` first, then cancels the registration. Every interleaving is covered:
  - waiter still queued → removed, callback never runs;
  - waiter already dequeued but not yet called → callback returns `false` → the primitive hands back what it won;
  - already delivered → nothing to undo (the consumer owns it).
- Per primitive (waiters are removed by reference identity, `eq`, with a CAS loop on the existing `AtomicReference` state):
  - **RxSemaphore**: cancel removes the waiter, then re-runs `satisfyWaiters` on the rest (a removed large head request may unblock smaller ones behind it). Callback `false` → `releaseN(n)`.
  - **RxBoundedQueue.take**: cancel removes the taker. Callback `false` → the element goes back: to the next waiting taker if any, else to the **front** of `elements`. This may exceed `capacity` by one transiently (offerers may have filled the queue meanwhile); keeping order matters more than the strict bound.
  - **RxBoundedQueue.offer** (waiting): cancel removes the offerer before acceptance, so the element is never enqueued. Once accepted (element moved into the queue), the offer stands even if the run was cancelled — the element stays for the next taker; the cancel lost the race, as with cancelling a completed future.
  - **RxDeferred.get / RxFiber.join**: cancel removes the waiter; the callback's result is ignored (nothing to hand back).
- Tests in `RxAsyncOpTest` (cross-platform, deterministic): cancel-then-release leaves the permit available; cancelled head `acquireN(2)` lets a queued `acquire` through; cancel-then-offer leaves the element for the next taker; cancelled waiting offer is not enqueued; `waiting` counts drop to 0 after cancel; `timeout` on a waiting acquire returns the permit. A JVM stress test races `release` against `cancel` and checks the permit count is conserved.
- ADR: update Consequences — waiters unregister on cancel; accepted offers stand; transient `capacity + 1`.

## Alternatives and Why Not?

- **Keep `Unit` callback, hand back inside the cancel only**: cancel cannot tell whether a dequeued-but-not-yet-delivered waiter's result was delivered, so it either double-releases or leaks. The `Boolean` return makes the runner's `done` flag the single arbiter.
- **Return the queue element to the back / to an offerer slot to keep the strict capacity**: reorders elements; a one-element transient overflow is the smaller surprise.
- **Undo an accepted offer by removing the element from the queue**: the element may already be taken; there is no consistent undo.
