# Rate Limiter Documentation

## Problem

The control module ships a JVM-only `RateLimiter` with three algorithms (token bucket, fixed window, sliding window), but it is not documented on the docs site. Users asking "does uni have rate limiting / leaky-bucket utilities?" cannot discover it.

## Scope

Add documentation only — no source changes. Out of scope: adding a dedicated leaky bucket algorithm.

## Deliverables

1. `docs/control/rate-limiter.md` — user-facing guide covering:
   - When to use each algorithm (token bucket vs fixed window vs sliding window)
   - Builder API for token bucket (permitsPerSecond, burstSize, warmupPeriod, ticker)
   - Factory methods for fixed/sliding window
   - Blocking (`acquire`) vs non-blocking (`tryAcquire`) semantics
   - Composition with rest of control module (Retry, CircuitBreaker)
   - Note on token bucket vs leaky bucket (token bucket permits bursts; for strict smoothing, set `burstSize = 1`)
2. Sidebar entry in `docs/.vitepress/config.mts` (two locations: `/guide/` and `/` sidebars).
3. Entry in `docs/control/index.md` overview table.

## Non-goals

- No new leaky-bucket implementation.
- No changes to `RateLimiter.scala`.
- No Book chapter changes.

## Notes

- Token bucket with `burstSize = 1` effectively approximates leaky-bucket output smoothing for the "I need steady output" use case. Call this out explicitly so users don't need to file a feature request.
- `RateLimiter` is JVM-only today (`uni/.jvm/src/main/scala/wvlet/uni/control/RateLimiter.scala`). Mention the platform caveat.
