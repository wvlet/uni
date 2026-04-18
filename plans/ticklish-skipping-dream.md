# Plan: Add Zip Archive Support to wvlet.uni.io

## Context

The uni IO module already has cross-platform Gzip compression (`Gzip.scala` + platform `GzipCompat` traits). Zip archive support (create/extract/list) is needed to complete the compression story. This follows the exact same cross-platform pattern as Gzip.

## API Design

```scala
// Shared: uni-core/src/main/scala/wvlet/uni/io/Zip.scala
case class ZipEntry(
  name: String,
  size: Long,
  compressedSize: Long,
  isDirectory: Boolean,
  lastModified: Long  // epoch millis
)

trait ZipApi:
  def create(target: IOPath, sources: Seq[IOPath]): Unit
  def extract(archive: IOPath, destination: IOPath): Unit
  def list(archive: IOPath): Seq[ZipEntry]

object Zip extends ZipCompat
```

## Platform Implementations

### JVM (`uni-core/.jvm/src/main/scala/wvlet/uni/io/ZipImpl.scala`)
- Use `java.util.zip.ZipOutputStream` for create
- Use `java.util.zip.ZipInputStream` for extract
- Use `java.util.zip.ZipFile` for list (efficient — reads central directory)
- Handles nested directories, preserves relative paths

### Native (`uni-core/.native/src/main/scala/wvlet/uni/io/ZipImpl.scala`)
- Same code as JVM — Scala Native supports `java.util.zip.*` when linked with `-lz`
- The existing Native GzipImpl already uses `java.util.zip.GZIP*` classes successfully

### JS/Node.js (`uni-core/.js/src/main/scala/wvlet/uni/io/ZipImpl.scala`)
- Implement ZIP format handling using Node.js `zlib` (deflateRawSync/inflateRawSync) + `Buffer` for binary data
- Implement CRC32 in pure Scala (shared or JS-local utility, ~20 lines)
- ZIP format structures: local file headers, central directory, EOCD — straightforward binary format
- Browser: throw `UnsupportedOperationException` (same pattern as Gzip)

### Key Node.js facades needed:
- `zlib.deflateRawSync(buffer)` / `zlib.inflateRawSync(buffer)` — already partially in GzipImpl
- `Buffer` — for binary data construction/parsing (read/write UInt32LE, UInt16LE, etc.)

## Files to Create/Modify

### New Files
1. `uni-core/src/main/scala/wvlet/uni/io/Zip.scala` — shared API trait + ZipEntry + CRC32 utility
2. `uni-core/.jvm/src/main/scala/wvlet/uni/io/ZipImpl.scala` — JVM ZipCompat
3. `uni-core/.native/src/main/scala/wvlet/uni/io/ZipImpl.scala` — Native ZipCompat (same as JVM)
4. `uni-core/.js/src/main/scala/wvlet/uni/io/ZipImpl.scala` — JS ZipCompat (Node.js zlib-based)
5. `uni/src/test/scala/wvlet/uni/io/ZipTest.scala` — cross-platform tests

### Reference Files (patterns to follow)
- `uni-core/src/main/scala/wvlet/uni/io/Gzip.scala` — API trait pattern
- `uni-core/.jvm/src/main/scala/wvlet/uni/io/GzipImpl.scala` — JVM impl pattern
- `uni-core/.js/src/main/scala/wvlet/uni/io/GzipImpl.scala` — JS impl + browser detection
- `uni-core/.native/src/main/scala/wvlet/uni/io/GzipImpl.scala` — Native impl pattern
- `uni/src/test/scala/wvlet/uni/io/GzipTest.scala` — test pattern

## Implementation Details

### create(target, sources)
- Walk each source: if file, add as entry; if directory, recursively add all files
- Entry names use relative POSIX paths (forward slashes)
- Create parent directories of target if needed

### extract(archive, destination)
- Read each entry from the archive
- Create intermediate directories as needed
- Write file contents to destination/entryName
- Skip directory entries (just create directories)

### list(archive)
- Return all entries with metadata (name, size, compressedSize, isDirectory, lastModified)
- JVM: use ZipFile for efficient central directory reading
- JS: parse the ZIP end-of-central-directory and central directory records

### CRC32 (for JS implementation)
- Pure Scala CRC32 implementation using standard polynomial (0xEDB88320)
- Needed for ZIP local file headers and central directory entries
- ~20 lines using lookup table

## Tests

```scala
class ZipTest extends UniTest:
  FileSystemInit.init()
  private lazy val tempDir = FileSystem.createTempDirectory("zip-test")

  test("create and extract files") { ... }
  test("create and extract nested directories") { ... }
  test("list entries") { ... }
  test("handle empty archive") { ... }
  test("round-trip preserves file content") { ... }
```

## Verification

1. `./sbt coreJVM/compile coreJS/compile coreNative/compile` — all platforms compile
2. `./sbt "uniJVM/testOnly *ZipTest"` — JVM tests pass
3. `./sbt "uniJS/testOnly *ZipTest"` — JS/Node.js tests pass
4. `./sbt "uniNative/testOnly *ZipTest"` — Native tests pass
5. `./sbt scalafmtAll` — formatting

## Implementation Notes

### Scala Native Quirks
- `ZipFile` throws `ZipException` for empty archives (unlike JVM). Used try-catch to return `Seq.empty`.
- `ZipInputStream.getNextEntry` returns `-1` for size/compressedSize when reading sequentially. Must use `ZipFile` for accurate metadata.

### JS Implementation
- `js.Date(millis)` resolves to companion object `apply(): String`, not constructor. Must use `new js.Date(millis)`.
- `js.Date` constructor takes `Int` parameters, not `Double`.
- Zip slip protection needs `path + separator` for string prefix checks to avoid false positives (e.g., `/tmp/out-evil` vs `/tmp/out`).
- ZIP format requires UTF-8 flag (bit 11 = 0x800) in general purpose bit flags for non-ASCII filename interoperability.
- Keep compressed data as `Uint8Array` throughout to avoid triple byte array conversion.
- `InputStream.read` returns -1 at EOF; use `while len >= 0` not `while len > 0`.

### PR: wvlet/uni#476
