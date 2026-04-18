# 4. Logging That Finds You

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Logs in a Uni application are written for **a human reading output**,
not for a log-aggregation pipeline to parse. That framing shapes what
Uni's logger does, and — just as importantly — what it chooses not to
do.

Concepts introduced:

- The `LogSupport` trait and why mixing it in beats passing a
  `Logger` around.
- Source-location capture: how Uni captures file and line at compile
  time, and what that means at runtime.
- Log levels and how to choose between them without lying to your
  future self.
- Per-package log configuration at runtime.
- Why Uni's logger intentionally has **no structured-logging API**:
  logs are developer context, not machine-parsed records. When you
  need fields, IDs, and correlation, reach for a telemetry tool
  (metrics, traces) instead of encoding state into log lines.
- Formatting choices for terminals and for log files.

## Reference you can read now

- [Logging (module reference)](/core/logging)

[← 3. Wiring with Design](./ch03-00-design) | [Next → 5. JSON & MessagePack](./ch05-00-data)
