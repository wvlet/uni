# Plan: RPC Client Code Generation with sbt 2 Plugin

## Goal

Generate type-safe RPC client code from Scala 3 service traits via an sbt 2 plugin.

## Architecture

### Design Principles

1. **Use Surface for method metadata** — `Surface.methodsOf[T]` (inline macro) extracts method
   signatures at compile time in the generated code. No TASTy inspector or special compiler
   dependencies needed.
2. **Reflection for class discovery** — sbt plugin uses Java reflection to scan compiled classes
   for service traits (same approach as Airframe's `RxRouterProvider` pattern).
3. **Codegen lives in `uni`** — no separate module. JVM-specific code in `uni/.jvm/`.
4. **In-process codegen** — sbt 2's Scala 3 metabuild enables direct library calls. No forked
   JVM, no Coursier download, no JSON IPC (unlike sbt-airframe).
5. **CodeFormatter for source generation** — Wadler's "A Prettier Printer" algorithm in
   `uni-core` (`wvlet.uni.text.CodeFormatter`) produces well-formatted code via Doc trees.

### Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `RPCClient` | `uni/src/.../rpc/RPCClient.scala` | Runtime dispatch — mirrors RPCRouter but sends requests. Uses Surface + MethodCodec |
| `ServiceScanner` | `uni/.jvm/.../codegen/ServiceScanner.scala` | Reflection-based: classloads traits to extract method signatures |
| `RPCClientGenerator` | `uni/.jvm/.../codegen/client/RPCClientGenerator.scala` | Builds Doc trees → Scala source with typed method stubs |
| `HttpCodeGenerator` | `uni/.jvm/.../codegen/HttpCodeGenerator.scala` | Orchestrator: config parsing → scanning → generation → file writing |
| `HttpClientIR` | `uni/.jvm/.../codegen/HttpClientIR.scala` | IR data model: ServiceDef, MethodDef, TypeRef, CodegenConfig |
| `CodeFormatter` | `uni-core/src/.../text/CodeFormatter.scala` | Wadler pretty printer (cross-platform, reusable) |
| `UniPlugin` | `sbt-uni/.../UniPlugin.scala` | sbt 2.0.0-RC10 AutoPlugin |

### Generated Code Shape

```scala
object UserServiceClient:
  // Surface extracts method metadata at compile time
  private val rpc = RPCClient.build(
    Surface.of[example.api.UserService],
    Surface.methodsOf[example.api.UserService]
  )

  class SyncClient(client: HttpSyncClient):
    def getUser(id: Long): User =
      rpc.callSync[User](client, "getUser", Seq(id))
    def createUser(name: String, email: String): User =
      rpc.callSync[User](client, "createUser", Seq(name, email))

  class AsyncClient(client: HttpAsyncClient):
    def getUser(id: Long): Rx[User] =
      rpc.callAsync[User](client, "getUser", Seq(id))

end UserServiceClient
```

## Scope (this PR)

- [x] `RPCClient` runtime class — client-side mirror of RPCRouter
- [x] `ServiceScanner` — reflection-based service trait scanning
- [x] `RPCClientGenerator` — Doc-tree code generation with CodeFormatter
- [x] `HttpCodeGenerator` — orchestrator with config parsing and file caching
- [x] `CodeFormatter` in uni-core — Wadler pretty printer (ported from wvlet)
- [x] `sbt-uni/` — sbt 2.0.0-RC10 plugin with scripted test
- [x] All codegen merged into `uni` module (no separate `uni-http-codegen`)

## Future

- REST client generation (from `@Endpoint`-annotated traits)
- Marker trait for auto-discovery (like Airframe's `RxRouterProvider`)
- Error messages, incremental compilation, documentation
