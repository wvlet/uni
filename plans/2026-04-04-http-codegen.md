# Plan: uni-http-codegen — RPC Client Code Generation (Phase 1)

## Goal

Add a `uni-http-codegen` JVM-only module that generates type-safe RPC client code from Scala 3 traits.
This is the foundation for an sbt plugin that will automate client stub generation (Phase 2).

## Architecture

### Module Structure
- `uni-http-codegen/` — JVM library published to Maven Central
- Depends on `uni.jvm` (for HTTP types) and `scala3-tasty-inspector` (for reading .tasty files)

### Key Components

1. **HttpClientIR** — Data model: `ServiceDef`, `MethodDef`, `ParamDef`, `TypeRef`, `CodegenConfig`, `ClientType`
2. **TastyServiceScanner** — Reads compiled `.tasty` files using `scala3-tasty-inspector` to extract trait method signatures, parameter types, return types, and `@Endpoint` annotations
3. **RPCClientGenerator** — Takes `ServiceDef` + `CodegenConfig` and produces Scala source for sync/async client classes
4. **HttpCodeGenerator** — Orchestrator: config string parsing → TASTy scanning → code generation → file writing with hash-based caching

### Discovery: TASTy-based (no reflection)

Unlike airframe's classloading approach, we read `.tasty` files directly — no forked JVM, no JSON IPC.
The generated code uses `Weaver.of[T]` inline for serialization (resolved at compile time of the generated source).

### Generated Code Shape (RPC)

```scala
object UserServiceClient:
  class SyncClient(client: HttpSyncClient):
    def getUser(id: Long): User =
      val jsonParts = Seq("\"id\":" + Weaver.of[Long].toJson(id))
      val req = Request.post("/com.example.api.UserService/getUser")
        .withJsonContent("{\"request\":{" + jsonParts.mkString(",") + "}}")
      val resp = client.send(req)
      Weaver.of[User].fromJson(resp.contentAsString.getOrElse(""))
```

## Phase 1 Scope (this PR)

- [x] Add `httpCodegen` module to `build.sbt`
- [x] `HttpClientIR.scala` — IR data model
- [x] `TastyServiceScanner.scala` — TASTy file reader
- [x] `RPCClientGenerator.scala` — Sync/async RPC client source generation
- [x] `HttpCodeGenerator.scala` — Orchestrator with config parsing and file writing
- [x] Tests: IR model, config parsing, RPC generation, TASTy scanning with real traits
- [x] `sbt-uni-codegen/` — sbt 2.0.0-RC10 plugin with AutoPlugin + scripted test

### Key Validation: sbt 2 In-Process Codegen

The sbt 2 plugin validates the core design hypothesis: sbt 2's Scala 3 metabuild enables
**direct in-process calls** to `uni-http-codegen` without forking a JVM. This eliminates the
Coursier download → tar.gz extraction → shell process → JSON IPC pattern used by sbt-airframe.

The scripted test proves the full pipeline:
1. Compile API trait → .tasty file
2. Plugin finds .tasty in dependent project's class directory
3. TastyServiceScanner extracts method metadata
4. RPCClientGenerator produces Scala source
5. Generated code compiles alongside user code

### Weaver Serialization Strategy

- **Primitive params** (`String`, `Long`, etc.): `summon[Weaver[T]]` finds pre-built givens from `PrimitiveWeaver`
- **Complex return types** (case classes): `Weaver.of[T]` uses compile-time derivation via `WeaverDerivation`

## Future Phases

- Phase 3: REST client generation (from `@Endpoint`-annotated traits)
- Phase 4: Error messages, incremental compilation, documentation
