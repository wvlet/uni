# RouterHandler and RPCHandler live in uni; uni-netty is an optional backend

- Status: Accepted
- Date: 2026-11-20

## Context

`Http.server` runs with `uni` alone on every platform: the built-in socket server on the JVM (JDK 24+, ADR 2026-11-18) and Scala Native, and Node.js on Scala.js. The built-in server now matches Netty's HTTP/1.1 defenses and features (ADR 2026-11-18, socket server Netty parity). But `RouterHandler` (the `@Endpoint` annotation router) and `RPCHandler` were still in `uni-netty`, in package `wvlet.uni.http.netty`, although neither used a Netty class: both only adapt `Router` / `RPCDispatcher` from `uni` to an `RxHttpHandler`. So the docs kept saying "add `uni-netty` to serve REST or RPC on the JVM", and the annotation router was documented as JVM-only.

## Decision

- **Move the handlers into `uni`'s shared sources**: `wvlet.uni.http.router.RouterHandler` and `wvlet.uni.http.rpc.RPCHandler`. Both compile and pass the same tests on the JVM, Scala.js, and Scala Native (`RouterHandlerTest`), so the annotation router and RPC work on every server backend.
- **Keep deprecated aliases in `uni-netty`** (`type` + `val` in `wvlet.uni.http.netty`), as done for the `RPCStatus` renames (ADR 2026-11-16). Source code that imports the old names keeps compiling with a deprecation warning; binaries compiled against the old classes must be rebuilt, which is acceptable before 1.0.
- **Describe `uni-netty` as optional** everywhere (README, install pages, HTTP/RPC docs, book, AI-agent page, plugin skill): add it for Netty's tuning options (native transport, handler executor threads, graceful shutdown, `noRequestDecompression`) or to serve on a JDK older than 24. Handlers, routers, and filters don't change when it is added or removed.

## Alternatives

- **Leave the handlers in `uni-netty` and document `RPCDispatcher(router).dispatch` as the Netty-free path.** Rejected: two names for the same thing, and the annotation router would stay unavailable without Netty for no technical reason.
- **Move without aliases.** Rejected: every existing REST or RPC server (examples, Wvlet) imports `wvlet.uni.http.netty.RouterHandler`; a source break buys nothing.
