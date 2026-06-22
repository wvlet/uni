# 8. Living With Failure — Retry, Circuit Breakers, Resources

A call across a network will fail. Not *if* — *when*. The interesting
question is what you do about it, and there are three different answers
depending on the failure:

- It was a blip — the packet dropped, the server hiccuped. **Retry it.**
- The backend is genuinely down — every call is failing. **Stop hammering
  it.**
- The call failed and left a connection open. **Clean it up anyway.**

Uni gives you one primitive for each: `Retry`, `CircuitBreaker`, and
`Control.withResource`.

## Retry a transient failure

Wrap the flaky call in a retry policy and `run` it:

```scala
import wvlet.uni.control.Retry

val result = Retry
  .withBackOff(maxRetry = 3)
  .run {
    callFlakyService()   // runs up to 4 times: 1 try + 3 retries
  }
```

`withBackOff` waits longer between each attempt — a tenth of a second,
then more, then more — instead of retrying in a tight loop. That spacing
matters: a service that just hiccuped needs a moment, and a hundred
clients retrying in lockstep would knock it over again the instant it
recovers. For that lockstep problem specifically, `Retry.withJitter()`
randomizes the delays so clients don't synchronize. If every attempt
fails, the policy gives up by throwing `MaxRetryException`. The
[retry reference](/control/retry) covers tuning the intervals and
choosing *which* errors to retry.

### Why you can't retry everything

Retry has a precondition that is easy to miss: the operation must be
**idempotent** — safe to run more than once. Reading a URL is idempotent;
running it twice returns the same page. `POST /charge-card` is *not* —
run it twice and you've billed the customer twice.

Before you wrap a call in `Retry`, ask: if this runs twice, is the world
the same as if it ran once? If not, retrying turns a transient failure
into a correctness bug. Make the call idempotent first (an idempotency
key, an upsert), *then* retry it.

## Stop calling a backend that's down

Retry handles a blip. It is exactly the wrong tool when a backend is
*actually* down: now every request retries four times before failing, so
you've quadrupled the load on a service that's already on the floor, and
every caller waits through the full backoff to get an error it could have
had instantly.

A `CircuitBreaker` watches the failure rate and, once it crosses a
threshold, "opens" — failing calls instantly without touching the backend
at all:

```scala
import wvlet.uni.control.{CircuitBreaker, CircuitBreakerOpenException}

val breaker = CircuitBreaker.withConsecutiveFailures(5)

try
  breaker.run {
    callBackend()
  }
catch
  case e: CircuitBreakerOpenException =>
    // fail fast — the breaker is open, we didn't even try
    useFallback()
```

It runs a small state machine: **closed** (calls pass through), **open**
(calls fail instantly), and **half-open** (after a cooldown, it lets one
trial call through to see if the backend recovered). The point is to
*fail fast* while a dependency is down — protecting both the caller, who
gets an immediate answer, and the backend, which gets room to recover
instead of a retry storm. `withConsecutiveFailures(5)` trips after five
failures in a row; `withFailureThreshold` trips on a failure *rate*. See
the [circuit breaker reference](/control/circuit-breaker) for the states
and recovery policy.

## Clean up no matter what

Both tools above can fail the call. When they do, anything the call
opened — a file, a socket, a connection — still needs to close.
`Control.withResource` guarantees it:

```scala
import wvlet.uni.control.Control

Control.withResource(openConnection()) { conn =>
  conn.query("select 1")
}   // conn.close() runs here — even if query threw
```

This is the loan pattern: `withResource` lends you the resource for the
duration of the block and closes it when the block exits, on the normal
path *and* the exception path. You can't forget the `close()`, because
you never wrote it.

If that mechanism feels familiar, it should — it is the same guarantee
`Design`'s `onShutdown` gives a whole session
([Chapter 3](./ch03-00-design)). `Control.withResource` is the local, block-scoped
version; `Design` is the application-scoped version. Reach for
`Control.withResource` when a resource lives for one operation, and for a `Design`
lifecycle hook when it lives for the program.

## Putting them together

A hardened call to a flaky dependency uses all three at once: a
`Control.withResource` for the connection, a `CircuitBreaker` to fail fast when the
dependency is down, and `Retry` to ride out the blips that get through:

```scala
import wvlet.uni.control.{CircuitBreaker, Control, Retry}

val breaker = CircuitBreaker.withConsecutiveFailures(5)

def fetch(url: String): String =
  breaker.run {
    Retry.withBackOff(maxRetry = 3).run {
      Control.withResource(openConnection()) { conn =>
        conn.get(url)
      }
    }
  }
```

Read it from the inside out: the resource is always cleaned up, retries
absorb transient failures, and the breaker stops the whole thing from
piling onto a backend that's already down. Each layer does one job, and
they compose because each is an ordinary value wrapping an ordinary
block.

## What you have, what comes next

You can now write code that survives the network:

- **`Retry.withBackOff(...).run { }`** absorbs transient failures —
  *only for idempotent operations*.
- **`CircuitBreaker.run { }`** fails fast when a backend is down, so you
  stop making it worse.
- **`Control.withResource(r) { }`** closes resources on every path, the
  block-scoped cousin of `Design`'s `onShutdown`.

That closes Part IV: your services can react ([Rx](./ch07-00-rx)) and
recover (this chapter). Next, [Part V](./ch09-00-http) puts them on the
network for real — building HTTP clients and servers, then typed RPC on
top.

[← 7. Rx, the Composable Stream](./ch07-00-rx) | [Next → 9. HTTP Clients and Servers](./ch09-00-http)
