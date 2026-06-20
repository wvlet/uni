# Appendix C: Glossary

Short definitions of terms as this book uses them. The intent is to make
cross-references precise, not to define every word in Scala. Each entry
points to the chapter where the concept is developed.

**Binding** — One entry in a [Design](#design): a rule for how to produce
a value of some type. Created with `bindSingleton`, `bindInstance`,
`bindImpl`, or `bindProvider`. See [Chapter 3](./ch03-00-design).

**Cancelable** — The handle returned by `Rx.subscribe` / `Rx.run`. Calling
`cancel` tears down the subscription and stops further values. Holding and
releasing it is how `Rx` avoids leaked streams. See
[Chapter 7](./ch07-00-rx).

**Circuit breaker** — A control-flow primitive that stops calling a
backend once its failure rate crosses a threshold, "opening" to fail fast
until the backend recovers. See [Chapter 8](./ch08-00-control).

**Codec** — A bidirectional translator between bytes (JSON or MessagePack)
and Scala values. In Uni the codec is a [Weaver](#weaver). See
[Chapter 6](./ch06-00-data).

**Cross-project** — An sbt module declared once with `crossProject` and
compiled for the JVM, Scala.js, and Scala Native. Shared code lives in
`src/`; platform-specific code in `.jvm` / `.js` / `.native`. See
[Chapter 11](./ch11-00-cross-platform).

**Design** — An immutable description of how types are wired together —
which implementations satisfy which dependencies, and which values live as
singletons. A `Design` is a value: building it runs nothing. See
[Chapter 3](./ch03-00-design).

**Launcher** — The annotation-driven CLI argument parser. It populates a
plain case class from `Array[String]` using `@option` and `@argument`. See
[Chapter 2](./ch02-00-cli-app).

**LogSupport** — The trait a class mixes in to get `trace` / `debug` /
`info` / `warn` / `error` methods and a logger named after the class. Log
messages carry their source `(file:line)`, captured at compile time. See
[Chapter 5](./ch05-00-logging).

**Resource** — A control primitive (`Resource.withResource`) that lends
you a resource for a block and closes it on exit, on both the normal and
exception paths — the loan pattern. See [Chapter 8](./ch08-00-control).

**Retry** — A policy (`Retry.withBackOff`) that re-runs a failing
operation with increasing delays. Safe only for idempotent operations. See
[Chapter 8](./ch08-00-control).

**RPC** — Calling a remote service through a shared Scala trait, so the
client and server are checked against one contract by the compiler. See
[Chapter 10](./ch10-00-rpc).

**Rx** — Uni's abstraction for a value that arrives later or changes over
time: a lazy description of zero or more values that you compose with
`map` / `flatMap` / `filter` and consume with `subscribe`. See
[Chapter 7](./ch07-00-rx).

**RxVar** — A mutable `Rx` you can watch: created with `Rx.variable`,
updated with `:=`, and observed by subscribers. See
[Chapter 7](./ch07-00-rx).

**Session** — The runtime context `Design.build` (or `withSession`) opens:
it owns the singletons it constructs and runs their `onStart` / `onShutdown`
lifecycle hooks. A session has a lifetime; closing it tears the graph
down. See [Chapter 3](./ch03-00-design).

**Surface** — Compile-time type reflection. It captures a type's structure
(fields, parameters, type arguments) for the codec layer to use, without
runtime reflection — which is what lets derivation work on Scala.js and
Native. See [Chapter 6](./ch06-00-data).

**Weaver** — Uni's derivation-based codec. One `Weaver[A]` serializes a
type to JSON *or* MessagePack; you get it with `derives Weaver`. See
[Chapter 6](./ch06-00-data).

[← Appendix B: Uni and Airframe](./appendix-b-airframe) | [Back to the Book](./)
