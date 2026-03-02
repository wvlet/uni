# uni-core

Pure-Scala essential libraries. Cross-platform: JVM, Scala.js, Scala Native.

## Constraints

- Zero external dependencies — pure Scala only
- No JVM-only APIs (e.g., `java.io.File`, `java.nio`, `java.net`)
- All code must compile on JVM, JS, and Native
- This is the foundation module; everything else depends on it

## Testing

```bash
./sbt coreJVM/test        # JVM tests
./sbt coreJS/test         # Scala.js tests
./sbt coreNative/test     # Scala Native tests
```

See `docs/dev/unitest-guide.md` for assertion patterns.
