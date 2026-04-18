# 3. Wiring with Design

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Part III goes back to the `Design.build` shape you already met in
Parts I and II, and explains it properly. By the end of it you should
be able to look at an unfamiliar `Design.newDesign...` block and know
exactly what it is saying.

Concepts introduced:

- Why constructor injection is preferred over globals, service
  locators, and runtime reflection.
- `bindSingleton`, `bindImpl`, `bindInstance`, `bindProvider` — which
  to reach for, and why.
- `design.build[A] { ... }` as the convenience entry point, and
  `withSession` underneath it for cases where you need access to the
  session itself.
- Sessions, child sessions, and scoping: when one instance lives for
  the whole process vs. per-request.
- Lifecycle hooks (`onStart`, `onShutdown`) and how they compose with
  sessions.
- Overriding a design for tests (the foundation for Chapter 12).
- Common mistakes and how to read the error messages Design produces.

## Reference you can read now

While this chapter is in progress, the reference page is complete:

- [Design (module reference)](/core/design)

[← 2. A URL Fetcher](./ch02-00-cli-app) | [Next → 4. Logging That Finds You](./ch04-00-logging)
