# RxSource streams pull one event at a time and continue on delivery

## Context

`RxRunner` read an `RxSource` (`Rx.queue`, `RxBoundedQueue`, `RxBlockingQueue`) in a loop that called `source.next` again as soon as it had subscribed to the previous result, whether or not the event had arrived. On an empty `RxQueue`, `next` returns `Rx.future` of the same waiting promise every time, so the loop spun on the subscribing thread (`subscribe` never returned) and added a callback to that promise on every iteration until the JVM ran out of heap. Cancelling pushed `OnError(InterruptedException)` into the source itself, which a later subscriber of the same queue then received, and which permanently completed an `RxBoundedQueue`.

## Decision

- The loop calls `next` once per event. When the event is delivered while `run(next)` is still on the stack (an already queued value, or a blocking `RxBlockingQueue.take`), the loop continues; when it arrives later, the delivery callback continues reading. A small state flag decides which side continues, so exactly one does and the stack does not grow.
- A downstream `Stop` now ends reading in non-continuous mode, as for other sources; before, the loop ignored it and kept consuming events.
- The inner `next` Rx ending with `OnError` (the effect threw) also continues reading, so continuous mode does not silently stop after one failing event.
- Cancellation is delegated to a new `RxSource.cancelNext()`, called only while a `next` is pending, and again after the subscription is stored if the cancel ran before it existed. The default keeps the old wake-up (`add(OnError(InterruptedException))`) for sources that block inside `next`; events delivered after cancellation are dropped.
- `RxQueue.next` is an `Rx.AsyncOp` over a queue of waiting callbacks instead of a shared `Promise` + `Rx.future`. An event goes straight to the first waiter on the producer's thread, and back to the front of the queue if that waiter was cancelled, so no event is lost in an `ExecutionContext` hop and concurrent subscribers no longer share one promise. Its `cancelNext` is a no-op because cancelling the `AsyncOp` unregisters the waiter.
- `RxBoundedQueue.next` maps a completed, empty queue to `OnCompletion` (`take` fails with `RxBoundedQueue.QueueCompleted`), and completing or failing the queue wakes every waiting taker; before, a taker that registered on an empty queue waited forever. Its `cancelNext` is a no-op for the same reason as `RxQueue`.

## Consequences

- `Rx.queue` and `RxBoundedQueue` can be subscribed before anything is added, including before `stop()` (`RxQueueTest`, shared across JVM, Scala.js, and Native).
- `RxQueue.put` now runs a waiting subscriber's code on the producer's thread, as `RxBoundedQueue` already did.
- A custom `RxSource` whose `next` waits asynchronously should override `cancelNext`; otherwise a cancelled subscription adds an `OnError` to it.
