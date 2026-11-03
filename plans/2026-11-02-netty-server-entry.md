# Netty.server: a JVM-only entry point for Netty

## Problem

#840 deprecated `NettyServer` (released in 2026.1.39) so that `Http.server` is the one way to start a server. That suits cross-platform code, but a project that only targets the JVM pays for portability it doesn't use:

- Netty settings are split across two values: shared ones on `Http.server`, Netty-only ones in `withBackend(Netty.options...)`.
- The backend is found through `ServiceLoader`, so forgetting `uni-netty` fails at `start()`, not at compile time.
- The result is `HttpServer`, not `NettyHttpServer`.

## Design

- Add `Netty.server: NettyServerConfig` next to `Netty.options` in `wvlet.uni.http.netty.Netty`. It returns the full typed Netty config, so all settings are in one place:
  ```scala
  Netty.server.withPort(8080).withHandlerExecutorThreads(8).withRxHandler(handler).start()
  ```
- Naming follows `Http.server` (portable) / `Netty.server` (JVM-only), and `Netty.options` (Netty settings for `Http.server`).
- Remove the deprecated `NettyServer` object (deprecated in 2026.1.39, one release ago): with `Netty.server` it would be a second JVM-only entry point. Keep `NettyHttpServer` as is; renaming it to `NettyServer` would match Airframe but break the `NodeHttpServer`/`NativeHttpServer` naming and give the old name a new meaning.
- Scala Native and Node are unchanged: no `Native.server` for now (add it later if users ask).

## Changes

- `uni-netty`: `Netty.server`, removal of the `NettyServer` object (the file becomes `NettyServerConfig.scala`), a test that starts `Netty.server`.
- Docs: `docs/http/server.md` gets a "JVM-only apps" section on when to pick `Netty.server` vs `Http.server`. `docs/guide/for-ai-agents.md` lists `Netty.server`.
- Skill: `plugins/uni/skills/uni/` (SKILL.md and references/http-server.md) mention `Netty.server`; bump plugin version.
- ADR: update `adr/2026-10-31-http-server-entry-point.md` (the decision that the per-backend entry points are deprecated now has a JVM-only exception).
