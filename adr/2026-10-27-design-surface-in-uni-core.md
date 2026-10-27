# Design and Surface live in uni-core

## Context

`uni-test` depends on `uni-core`, and `uni` depends on `uni-test` for its own tests, so `uni-test` cannot use anything in `uni`. When uni-test was introduced (#298) it left out AirSpec's Design integration (`design`, `initDesign`, Session-managed test arguments) for that reason, and #302 kept the core module on AirSpec because of the same cycle. The repo has resolved this kind of cycle before by moving foundation code below `uni-test`: logging (#305) and the minimal Rx core (#319, which renamed the module to `uni-core`).

UniTest fixtures that share servers, clients, or temp directories need the object lifecycle that Design already provides (a session per suite, a child session per test, shutdown in reverse creation order), and AirSpec-style test arguments need `Surface` to describe each argument type. Neither can be used from `uni-test` while they live in `uni`.

## Decision

- Move `wvlet.uni.surface` (Surface and its macros), `wvlet.uni.design` (Design, Session, lifecycle, including the platform-specific `AddShutdownHook` and the JVM `ChromeTracer`), and `wvlet.uni.util.SourceCode` from `uni` to `uni-core`.
- Package names do not change, so user code compiles unchanged; `uni` depends on `uni-core`, so `uni` users still get them.
- This move changes no behavior. The UniTest integration that uses it is a separate change.

## Consequences

- `uni-core` grows by about 5.5k lines (no new third-party dependencies) and now holds logging, the Rx core, object wiring, and Surface. Every `uni-test` user loads them.
- The Design and Surface tests stay in the `uni` module: `uni-core`'s own tests cannot use `uni-test` without a cycle.
- Classes move between artifacts, so `uni` and `uni-core` must come from the same release. They are always released together.
