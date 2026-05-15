# Remove legacy JVM-only `LogRotationHandler`

## Context

The user asked: "does uni's logger support log rotation? now that we have common file I/O, implementing log rotation handler as in airframe-log (based on logback-core) would be possible".

Answer: **yes, already supported.** PR #393 ("feature: Add cross-platform file log rotation handler and Gzip utility") shipped a cross-platform `FileLogHandler` (in `uni-core/src/main/scala/wvlet/uni/log/FileLogHandler.scala`) that runs on JVM, Scala.js (Node.js), and Scala Native using `wvlet.uni.io.FileSystem` and `wvlet.uni.io.Gzip`. It supports:

- Daily + size-based rotation
- Gzip compression of rotated files
- Cleanup of old rotated files past `maxNumberOfFiles`
- Builder-style `FileLogHandlerConfig` (`withXxx`, `noCompression`, `noRotation`)

PR #321 ("feature: Remove logback dependency by implementing native log rotation") had previously introduced a JVM-only `LogRotationHandler` (with a `FileHandler` no-rotation wrapper) directly against `java.io` / `java.util.zip`. That implementation now duplicates `FileLogHandler` and is **not referenced from anywhere outside its own file**:

```
$ grep -rn "LogRotationHandler\|uni.log.FileHandler" --include="*.scala" --include="*.md"
uni-core/.jvm/src/main/scala/wvlet/uni/log/LogRotationHandler.scala:40,49,61,276
```

No tests, no docs, no callers.

## Decision

Delete `uni-core/.jvm/src/main/scala/wvlet/uni/log/LogRotationHandler.scala`. Single canonical answer for "how do I rotate logs in uni": `FileLogHandler`.

## Scope

- Remove `uni-core/.jvm/src/main/scala/wvlet/uni/log/LogRotationHandler.scala` (contains both `LogRotationHandler` and its `FileHandler` wrapper).
- No replacements needed — `FileLogHandler` already covers both rotation and no-rotation use cases (via `.noRotation`).

## Out of scope

- Any new feature in `FileLogHandler`.
- Documentation site changes — the docs don't currently mention either handler.

## Verification

1. `./sbt compile` — proves nothing else linked against the deleted class.
2. `./sbt test` — broader sanity.
3. `./sbt scalafmtAll` — CI gate.
