# RxSource streams pull one event at a time and continue on delivery

## Context

`RxRunner` read an `RxSource` (`Rx.queue`, `RxBoundedQueue`, `RxBlockingQueue`) in a loop that called `source.next` again as soon as it had subscribed to the previous result, whether or not the event had arrived. On an empty `RxQueue`, `next` returns `Rx.future` of the same waiting promise every time, so the loop spun on the subscribing thread (`subscribe` never returned) and added a callback to that promise on every iteration until the JVM ran out of heap. Cancelling pushed `OnError(InterruptedException)` into the source itself, which a later subscriber of the same queue then received, and which permanently completed an `RxBoundedQueue`.

## Decision

- The loop calls `next` once per event. When the event is delivered while `run(next)` is still on the stack (an already queued value, or a blocking `RxBlockingQueue.take`), the loop continues; when it arrives later, the delivery callback continues reading. A small state flag decides which side continues, so exactly one does and the stack does not grow.
- A downstream `Stop` now ends reading in non-continuous mode, as for other sources; before, the loop ignored it and kept consuming events.
- Cancellation is delegated to a new `RxSource.cancelNext()`, called only while a `next` is pending. The default keeps the old wake-up (`add(OnError(InterruptedException))`) for sources that block inside `next`; events delivered after cancellation are dropped. `RxQueue` overrides it to forget its waiting promise, so the next event stays queued. `RxBoundedQueue` overrides it as a no-op, because cancelling its `take` already unregisters the waiter.

## Consequences

- `Rx.queue` and `RxBoundedQueue` can be subscribed before anything is added (`RxQueueTest`, shared across JVM, Scala.js, and Native).
- A custom `RxSource` whose `next` waits asynchronously should override `cancelNext`; otherwise a cancelled subscription adds an `OnError` to it.
