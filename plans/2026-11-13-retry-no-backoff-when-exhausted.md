# Retry: fail at once when no retry is left

## Goals

- A retryable failure that cannot be retried (e.g. `maxRetry = 0`) fails immediately instead of sleeping one backoff (~1–1.5 s) first.
- Retries that can continue keep their exact current behavior and timing.

## Background

- `RetryContext.run` and `runAsyncWithContext` both compute the next retry and sleep / `Rx.delay` its wait, and only afterwards check `canContinue` and raise `MaxRetryException`.
- `HttpClientConfig.noRetry` (used by `ElectronMain.newAsyncClient`, #906) hits this on every 503/504 or RPC UNAVAILABLE / DEADLINE_EXCEEDED: callers wait one backoff for nothing. `ElectronReverseRPCTest` failure cases take ~1.5 s each.

## Design

Lane: **Express** for the timing fix — clear diagnosis, bug fix, no always-gate area. The exception-type question below waits for the user's answer.

- After a retryable failure, still call `nextRetry(cause)` (so `retryCount`, `lastError`, and the `MaxRetryException` contents stay identical), then:
  - if the new context `canContinue`, sleep / delay and retry as before;
  - otherwise raise `MaxRetryException` at once, with no wait.
- Apply the same reordering to the sync `run` path and the async path.
- Tests (shared, so JVM / JS / Native): `maxRetry = 0` with a 10 s initial interval fails well under that bound, sync and async; `maxRetry = N` still makes N retries with backoff.

### Open decision: exception type under `noRetry` (not implemented)

- Today a `noRetry` HTTP call that gets a retryable error throws `HttpMaxRetryException("Reached max retry count 1/0: ...")` wrapping the real `HttpException`.
- Option A — keep as is: no API change; callers already matching `HttpMaxRetryException` keep working.
- Option B — when `maxRetry = 0`, raise the original error (`HttpException` / RPC error) directly: clearer for callers who never asked for retries, but changes the exception type they catch.
- Recommendation: decide separately; this PR keeps Option A unless the user picks B.

## Alternatives and Why Not?

- Check `canContinue` before `nextRetry`: skips the "Retrying in…" log too, but changes `retryCount` in `MaxRetryException` (1/0 → 0/0), which callers and messages rely on.
