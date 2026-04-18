# 4. Logging That Finds You

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Logging is load-bearing infrastructure: the difference between a
five-minute incident and a five-hour one is usually whether the right
log line was in the right place, at the right level, with the right
context.

Concepts introduced:

- The `LogSupport` trait and why mixing it in beats passing a
  `Logger` around.
- Source-location capture: how Uni captures file and line at compile
  time, and what that means at runtime.
- Log levels and how to choose between them without lying to your
  future self.
- Per-package log configuration at runtime.
- Structured logging and how to attach context (request IDs,
  user IDs) without string concatenation.
- Formatting for humans (terminals) vs. machines (log aggregators).

## Reference you can read now

- [Logging (module reference)](/core/logging)

[← 3. Wiring with Design](./ch03-00-design) | [Next → 5. JSON & MessagePack](./ch05-00-data)
