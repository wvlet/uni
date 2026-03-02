# uni

Main library collection: logging, DI, JSON/MessagePack, RPC/HTTP. Cross-platform: JVM, Scala.js, Scala Native.

## Constraints

- Depends on `uni-core` only
- Minimal external dependencies
- No JVM-only APIs in shared source — use `.jvm/`, `.js/`, `.native/` for platform-specific code
- HTTP client code must remain cross-platform compatible

## Patterns

- Config classes: `withXXX(...)` for all fields, `noXXX()` for optional fields
- Retry logic: use `wvlet.uni.control.Retry`, not custom implementations

## Testing

```bash
./sbt uniJVM/test
./sbt uniJS/test
./sbt uniNative/test
```
