# 10. One Codebase, Three Runtimes

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Scala 3 can target the JVM, JavaScript (via Scala.js), and native
binaries (via Scala Native). Uni is designed so that a single piece of
business logic compiles to all three. This chapter takes a working
JVM service and shows the minimum changes needed to also ship it as a
browser app and as a native binary.

Concepts introduced:

- `sbt-crossproject`: one `build.sbt`, three platforms.
- The `.jvm`, `.js`, and `.native` source folders and what belongs in
  each.
- Platform abstractions Uni gives you (HTTP, filesystem, timers) so
  your code stays identical.
- Things you *cannot* share: JVM-only file paths, browser-only DOM
  access, native-only syscalls — and how to isolate them.
- Building and distributing: a fat JAR, an optimized JS bundle, and a
  stripped native binary.

## Reference you can read now

- [Design Principles — Cross-Platform First](/guide/principles#cross-platform-first)
- [HTTP Client — platform notes](/http/client)

[← 9. Typed RPC](./ch09-00-rpc) | [Next → 11. Testing with UniTest](./ch11-00-testing)
