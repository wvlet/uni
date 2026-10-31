# Http.server: one server entry point for every platform

## Goals

- Write an HTTP/RPC server once and run it on the JVM, Scala Native, and Scala.js without platform-specific code.
- Make `examples/rpc-native` one shared `Main` and one shared test, so the launch post can say "the same code, no file changes between JVM and Native".

## Background

- Each platform has its own entry point today: `NettyServer` (uni-netty, JVM), `NativeServer` (uni, Native), `NodeServer` (uni, Scala.js). All produce an `HttpServer` from a shared `HttpServerConfig` trait, but the trait has no builder methods.
- `plans/2026-06-18-cross-platform-http-server.md` left out a unified `Http.server`: uni-netty depends on uni, so uni's JVM code cannot name Netty at compile time.
- `Http.client` already picks a backend per platform through `HttpCompat` (`adr/2026-05-15-cross-platform-init-handoff.md`).

## Design

Lane: Standard (new public API); direction approved on the design card ("Http.server + shared example").

- `Http.server` returns `PortableServerConfig`, a shared case class with the common surface: name, host, port, handler, filters, WebSocket routes, allowed origins and hosts, detailed errors.
- `start()` maps it to the platform's backend config, keeping each backend's own defaults: `NativeServerConfig` on Native, `NodeServerConfig` on Scala.js.
- On the JVM, `start()` finds an `HttpServerProvider` through `java.util.ServiceLoader`. uni-netty registers one in `META-INF/services`, so uni gains no dependency. With no provider on the classpath, `start()` fails with a message that says to add uni-netty.
- Backend-specific tuning stays on `NettyServer`/`NativeServer`/`NodeServer`, which remain the entry points for it.
- The example: one shared `Main` and `TodoApiTest` wired with Design, using `Http.server`. The JVM build adds uni-netty as a dependency; the Native build keeps Boehm GC. Tests run on both JVM and Native.
- An ADR records the reversal of the 2026-06-18 out-of-scope note.

## Alternatives and Why Not?

- Design binding per platform in the example: still one `NettyServer`/`NativeServer` line per platform.
- Reflection (`Class.forName`) instead of ServiceLoader: hard-codes a class name; ServiceLoader is the standard JVM plug-in mechanism and lets other backends register.
