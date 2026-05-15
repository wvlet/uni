# Accept plain `String` paths on `IO` / `FileSystem`

## Context

Today every `IO` / `FileSystem` call that takes a path requires an explicit
`IOPath` value:

```scala
val content = IO.readString(IO.path("data.txt"))
IO.writeString(IO.path("out.txt"), "hello")
```

`IO.path(...)` is purely a `String -> IOPath` wrapper here, and it leaks into
every code sample in `docs/core/filesystem.md`, `docs/core/io.md`, and
`docs/uni-walkthrough.md`. The repeated wrapping is boilerplate — most callers
just have a path string.

This change lets users pass a `String` anywhere an `IOPath` is currently
required, so the simple case becomes:

```scala
val content = IO.readString("data.txt")
IO.writeString("out.txt", "hello")
```

`IO.path(...)` stays for advanced uses (segment joining, `path / "child"`
composition, querying `fileName` / `parent` / `extension` etc.).

## Design

**Add `String`-accepting overloads on `FileSystemBase` that delegate to the
existing `IOPath`-typed methods via `IOPath.parse(...)`.** Each overload is a
one-liner concrete default in the trait, so platform implementations
(JVM / JS / Node / Native / Browser) don't change.

The overloads cascade automatically to the `IO` facade because
`IO` already does `export FileSystem.{ readString, writeString, ... }`, and
Scala 3 `export` of an overloaded member brings all overloads.

Same treatment for `Gzip` and `Zip`, which have a small surface
(`compressFile` / `decompressFile`, `create` / `extract` / `list`).

### Why overloads, not `given Conversion[String, IOPath]`?

A `given Conversion[String, IOPath]` would be one line, but in Scala 3 it
still requires `import scala.language.implicitConversions` at every call site
to silence the feature warning. That defeats the goal of "simpler docs and
simpler usage." Overloads are zero-ceremony, IDE-discoverable, and don't add
implicit-conversion surprises elsewhere (e.g. `Seq[String]` accidentally
becoming `Seq[IOPath]`).

The cost is ~30 one-line delegating methods in a single trait — well-contained.

### Files modified

1. `uni-core/src/main/scala/wvlet/uni/io/FileSystem.scala`
   - Add `String` overloads to `FileSystemBase` for all path-taking methods:
     - Single-path: `exists`, `isFile`, `isDirectory`, `info`, `readString`,
       `readBytes`, `readLines`, `readChunks`, `readStream`, `writeStream`,
       `writeString`, `writeBytes`, `appendString`, `appendBytes`, `list`,
       `createDirectory`, `createDirectoryIfNotExists`, `delete`,
       `deleteRecursively`, `deleteIfExists`, `permissions`, `setPermissions`,
       `owner`, `group`.
     - Two-path: `copy(source: String, target: String, options)`,
       `move(source: String, target: String, overwrite)`,
       `createSymlink(link: String, target: String)`, `readSymlink(link: String)`.
     - Async: `readStringAsync`, `readBytesAsync`, `writeStringAsync`,
       `writeBytesAsync`, `listAsync`, `infoAsync`, `existsAsync`.
   - `FileSystem` companion object: no edits — overloads on the trait
     are inherited as concrete delegates and don't need re-overriding.

2. `uni-core/src/main/scala/wvlet/uni/io/Gzip.scala`
   - Add `compressFile(source: String, target: String)` and
     `decompressFile(source: String, target: String)` defaults on the trait.

3. `uni-core/src/main/scala/wvlet/uni/io/Zip.scala`
   - Add `create(target: String, sources: Seq[String])`,
     `extract(archive: String, destination: String)`, `list(archive: String)`
     defaults on the trait.

4. `uni-core/src/main/scala/wvlet/uni/io/IO.scala`
   - Update the scaladoc example in the `object IO` comment to use the
     `String`-path style.
   - **No `export` changes needed** — overloaded `export` brings the new
     overloads automatically.

5. `uni/src/main/scala/wvlet/uni/control/IO.scala`
   - Simplify `readAsString(filePath: String) = FileSystem.readString(IOPath(filePath))`
     to `FileSystem.readString(filePath)` (now that the String overload exists).

### Docs updates

6. `docs/core/filesystem.md` — Rewrite read/write/list/copy/move examples to
   pass `String` directly. Keep the `IOPath` section (covers `IO.path(...)`,
   `/` composition, `fileName`, `parent`, etc.) and add a short note: "Any
   `String` path is accepted by `IO` operations; use `IO.path(...)` when you
   need an `IOPath` value for composition or queries."

7. `docs/core/io.md` — Replace `val path = IO.path("file.txt")` examples with
   direct string calls. Mention `IO.path` only in the section that builds up
   composed paths.

8. `docs/uni-walkthrough.md` — Update the IO subsection to show string-based
   usage; keep the one example that demonstrates `IO.path("/home/user") / "project" / "README.md"`
   because it specifically shows `IOPath` composition.

### Tests

9. `uni/.jvm/src/test/scala/wvlet/uni/io/IOFileSystemTest.scala`
   - Add a test case `"accept plain String paths"` that exercises
     `IO.writeString("...", ...)`, `IO.readString("...")`, `IO.exists("...")`,
     `IO.delete("...")`, and `IO.list("...")` using string paths from
     `tempDir` (still constructed via `IO.path(...)` since `tempDir` is an
     `IOPath`, then `.posixPath` gives us a string).
   - Existing `IO.path(...)` test cases stay — they verify the original API
     still works.

10. `uni-core/.jvm/src/test/scala/wvlet/uni/io/GzipTest.scala` and
    `ZipTest.scala`: add one case each showing the `String` overload works.
    (Skip if they already cover this via implicit `IOPath` `/` operator.)

## Compatibility

- Pure addition — no signatures removed, no defaults dropped.
- `IO.path(...)` continues to work and remains the recommended way to
  construct an `IOPath` for composition.
- Existing code compiles unchanged; only the docs and the trivial
  `control.IO.readAsString` delegation get tightened up.

## Verification

```bash
./sbt scalafmtAll
./sbt compile
./sbt coreJVM/test
./sbt "uni-coreJVM/testOnly *IOFileSystemTest"
./sbt "uni-coreJVM/testOnly *GzipTest"
./sbt "uni-coreJVM/testOnly *ZipTest"
./sbt test                       # full cross-build check
```

Spot-check the rendered docs locally (`pnpm docs:dev`) to confirm the
filesystem and IO sections still read correctly with the new examples.

## Out of scope

- No `given Conversion[String, IOPath]` (see rationale above).
- No removal or deprecation of `IO.path(...)` — it remains the explicit
  constructor.
- `createTempFile` / `createTempDirectory` keep their `Option[IOPath]`
  `directory` parameter as-is; callers can pass `None` or
  `Some(IO.path("..."))`. Adding a `String` version of an `Option` parameter
  isn't worth the surface area.
- `ProcessConfig.withWorkingDirectory(dir: IOPath)` is left as-is. Working
  directory is rarely set, and the typed call is more readable in process
  configuration than the path-as-string idiom.
