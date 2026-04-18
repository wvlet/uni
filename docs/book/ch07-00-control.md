# 7. Living With Failure — Retry, Circuit Breakers, Resources

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Production code spends most of its time either failing or recovering
from something that failed a moment ago. This chapter covers the three
Uni primitives that let you write that gracefully: `Retry`,
`CircuitBreaker`, and `Resource`.

Concepts introduced:

- Retry policies: exponential backoff, jitter, and which errors are
  safe to retry (and which are dangerous to).
- Circuit breakers: when a backend is clearly down, stop making it
  worse. The state machine (closed → open → half-open) and how Uni
  models it.
- Idempotency as a precondition for retry — and how to tell whether
  your call is idempotent.
- `Resource` for scoped resource lifetimes that compose with `Design`
  sessions.
- Combining the three: the canonical "HTTP call with retry, circuit
  breaker, and a bounded connection pool" pattern.

## Reference you can read now

- [Control Flow — Overview](/control/)
- [Retry](/control/retry)
- [Circuit Breaker](/control/circuit-breaker)
- [Resource](/control/resource)

[← 6. Rx, the Composable Stream](./ch06-00-rx) | [Next → 8. HTTP Clients and Servers](./ch08-00-http)
