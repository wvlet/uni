# Rate Limiter

Control the rate at which operations are performed. Unlike a semaphore that limits
*concurrent* operations, `RateLimiter` caps the *throughput* (e.g. 100 ops/sec).

`RateLimiter` is JVM-only and lives in `wvlet.uni.control`.

```scala
import wvlet.uni.control.RateLimiter
```

## Choosing an Algorithm

| Algorithm      | Bursts         | Best For                                              |
|----------------|----------------|-------------------------------------------------------|
| Token Bucket   | Allowed        | Smooth average rate with occasional bursts (default)  |
| Fixed Window   | At boundaries  | Simple per-window quotas (e.g. "100 requests/minute") |
| Sliding Window | None           | Strict rolling-window guarantees                      |

When in doubt, start with **token bucket** — it is the most flexible and the
cheapest (lock-free).

## Token Bucket

Tokens refill at a steady rate up to a bucket capacity (`burstSize`). Each
operation consumes one or more tokens. Permits bursts up to the bucket size and
then enforces the steady rate.

```scala
val limiter = RateLimiter
  .newBuilder
  .withPermitsPerSecond(100.0)
  .withBurstSize(20)
  .build()

// Blocks if the rate is exceeded; returns the wait time in ms
val waitedMs = limiter.acquire()

// Non-blocking variant
if limiter.tryAcquire() then
  callExternalService()
```

### Run a Block under a Limit

```scala
val result = limiter.withLimit {
  callExternalService()
}
```

### Acquire Multiple Permits

Useful when operations have different costs (e.g. a bulk API call):

```scala
limiter.acquireN(5)
limiter.withLimitN(10) {
  bulkUpload(batch)
}
```

### Builder Options

```scala
val limiter = RateLimiter
  .newBuilder
  .withPermitsPerSecond(50.0)   // steady rate
  .withBurstSize(10)            // max tokens stored
  .withWarmupPeriod(1000L)      // gradually ramp up over 1s
  .withName("external-api")     // for logs / metrics
  .withTicker(Ticker.systemTicker) // injectable for tests
  .build()
```

### Steady Output (Leaky-Bucket Behavior)

A token bucket with `burstSize = 1` refuses to burst, producing the smooth,
drain-at-constant-rate behavior commonly associated with a leaky bucket:

```scala
val smooth = RateLimiter.newBuilder
  .withPermitsPerSecond(10.0)
  .withBurstSize(1)
  .build()
```

## Fixed Window

Allows up to `maxOperations` per discrete window; the counter resets at window
boundaries. Simple and memory-efficient, but can spike at boundaries.

```scala
import java.util.concurrent.TimeUnit

val limiter = RateLimiter.fixedWindow(
  maxOperations = 100,
  windowDuration = 1,
  unit = TimeUnit.MINUTES
)
```

## Sliding Window

Allows up to `maxOperations` in any rolling `windowDuration`. More accurate than
fixed window, at the cost of tracking per-request timestamps.

```scala
val limiter = RateLimiter.slidingWindow(
  maxOperations = 100,
  windowDuration = 1,
  unit = TimeUnit.MINUTES
)
```

## Unlimited (No-op)

Useful as a default or in tests:

```scala
val limiter = RateLimiter.unlimited
```

## Inspecting State

All algorithms expose:

```scala
limiter.ratePerSecond          // configured rate
limiter.availablePermits       // current permits available
limiter.estimatedWaitTimeMillis // projected wait for the next permit
```

## Testing with a Manual Ticker

Inject `Ticker.manualTicker` to advance time deterministically in tests, avoiding
`Thread.sleep` in your test suite:

```scala
import wvlet.uni.control.{RateLimiter, Ticker}

val ticker  = Ticker.manualTicker
val limiter = RateLimiter
  .newBuilder
  .withPermitsPerSecond(1.0)
  .withBurstSize(1)
  .withTicker(ticker)
  .build()

limiter.tryAcquire() shouldBe true
limiter.tryAcquire() shouldBe false

ticker.tick(1_000_000_000L) // advance 1 second
limiter.tryAcquire() shouldBe true
```

## Composing with Retry and Circuit Breaker

`RateLimiter` composes naturally with the other control primitives. A common
pattern for calling a rate-limited external API:

```scala
import wvlet.uni.control.{CircuitBreaker, RateLimiter, Retry}

val limiter = RateLimiter.newBuilder.withPermitsPerSecond(10.0).build()
val breaker = CircuitBreaker.withConsecutiveFailures(5)

val result = Retry.withBackOff(maxRetry = 3).run {
  breaker.run {
    limiter.withLimit {
      callExternalService()
    }
  }
}
```

## Best Practices

1. **Pick the right algorithm** — token bucket for throughput smoothing, sliding
   window for strict quotas, fixed window when memory is tight.
2. **Size the burst deliberately** — a large burst increases tail pressure on
   downstream services; `burstSize = 1` enforces strict smoothing.
3. **Prefer `tryAcquire` at system boundaries** — shed load explicitly instead
   of blocking caller threads indefinitely.
4. **Inject a `Ticker` in tests** — avoid real sleeps; use `Ticker.manualTicker`.
5. **Combine with `CircuitBreaker` and `Retry`** — rate limiting alone does not
   protect against downstream outages.
