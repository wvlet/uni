# Http.server: one server entry point for every platform

## Context

Uni's HTTP server had one entry point per platform: `NettyServer` (uni-netty, JVM), `NodeServer` (Scala.js), and `NativeServer` (Scala Native). They share the `HttpServerConfig` trait, but a program still had to name its platform's server, so a cross-platform app needed a source file per platform. `plans/2026-06-18-cross-platform-http-server.md` left a unified `Http.server` out of scope: uni-netty depends on uni, so uni's JVM code cannot name Netty at compile time.

`examples/rpc-native` showed the cost: two `Main.scala` files that differed only in the server class, while Uni's pitch is that the same code runs on every platform.

## Decision

- **`Http.server` returns `PortableServerConfig`**, a shared case class with the settings every backend has: name, host, port, handler, filters, WebSocket routes, allowed origins and hosts, detailed errors. Its `start()` calls `HttpCompat.startServer`, which builds the platform's own config (`NativeServerConfig`, `NodeServerConfig`) from those fields, so each backend keeps its own defaults for everything else.
- **On the JVM, the backend is found through `java.util.ServiceLoader`.** uni defines `HttpServerProvider` in `uni/.jvm`; uni-netty implements it (`NettyServerProvider`) and lists it in `META-INF/services/wvlet.uni.http.HttpServerProvider`. uni gains no dependency. With no provider on the classpath, `start()` throws `IllegalStateException` naming uni-netty. If several providers are present, the first one ServiceLoader returns wins.
- **Backend-specific tuning stays on the per-platform entry points**, which are unchanged. `PortableServerConfig` only grows a setting when every backend supports it.
- **`Http` resolves the client's channel factory on first use, not at init.** `Http.defaultChannelFactory` was a `val` initialized from `HttpCompat.defaultHttpChannelFactory`, and on Native that loads `NativeHttpChannelFactory`, whose initializer calls `curl_global_init`. A server-only binary calling `Http.server` then linked libcurl. Now `Http` stores only the override from `setDefaultChannelFactory`, Native's `HttpCompat.defaultHttpChannelFactory` is a `def`, and the binary links no libcurl (checked by `.github/scripts/check-rpc-native.sh`). This keeps the value-handoff of `2026-05-15-cross-platform-init-handoff.md`: `Http` still reads a value from `HttpCompat`, just later.

## Alternatives

- **A `Design` binding per platform**: moves the server choice into one binding, but each platform still names its server class in its own source file.
- **Reflection (`Class.forName("wvlet.uni.http.netty.NettyServer$")`)**: hard-codes a class name. ServiceLoader is the JVM's standard plug-in mechanism and lets other backends register.

## Consequences

- `examples/rpc-native` is one shared `Main` and one shared `TodoApiTest`, wired with `Design`, and the test runs on the JVM and Native. The Native test binary is built in debug mode and links libcurl for the typed client (through `CurlBindings`' `@link`); the release binary does not.
- A JVM app that uses `Http.server` must add uni-netty. The failure is at `start()`, not at compile time.
