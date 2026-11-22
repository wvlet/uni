# Keep the host of a Windows UNC path in IOPath

## Goals

- `IOPath.parse("\\\\server\\share\\dir")` keeps the host and share. On Windows, `path` renders `\\server\share\dir` again, so `FileSystem` and `Parquet.dataset("\\\\server\\share\\*.parquet")` read the network share. Today they read `\server\share\dir` on the current drive.
- `parent`, `root`, and `normalize` stop at the share root, as they stop at `C:\`.

## Background

- `parse` replaces `\` with `/` and splits on `/`, so `\\server\share\dir` becomes `Seq("server", "share", "dir")` with `isAbsolute = true`. That is the same value as `/server/share/dir`, so the host is lost.
- PR #1099 made a drive (`C:`) the first segment of an absolute path and treated it as the root (`hasDrive`/`isDriveRoot`). A UNC root can work the same way.
- `IOPath(segments, isAbsolute)` is a public case class, so a new field would change the public API.
- Callers in uni-parquet (`listLocal`, `localPath`, glob splitting, `listedLocalFiles`) and the FileSystemImpl of each platform only work on segments or on `path`. They pick up the fix without changes. The one exception: the writer's `isTopLevel` checks for a filesystem root with a hard-coded drive test.

## Design

Lane: Express. It fixes a clear diagnosis and keeps the public API shape. It touches no always-gate area.

- A UNC root is a single first segment in POSIX form, `//server/share`, much as `C:` is. `posixPath` is `//server/share/dir`, and `path` on Windows is `\\server\share\dir`.
- `parse` reads `\\server\share` as UNC on every platform, as it already reads `C:`, so `posixPath`, `parent`, `root`, and `normalize` can be tested everywhere. No POSIX file system lists a path in that form.
- `//server/share` is UNC only when `IOPath.isWindows`. Linux and macOS read `//a/b` as `/a/b`, and the JVM and Native file systems list its files as `/a/b/...`, so a UNC root there would not match its own children in `relativeTo`.
- A UNC path starts with two separators followed by both a server and a share. `\\server` alone and `///x` keep today's parsing.
- `\\?\UNC\server\share` already becomes `\\server\share` before parsing (#1105), so it keeps its share too. Drive-relative paths like `C:foo` are out of scope.
- `parent` of `\\server\share` is None, `root` is `\\server\share`, and `..` stays at the share root.
- `resolve`, `relativeTo`, and `startsWith` compare segments, so a different host or share means a different root, as with a different drive.
- The writer's top-level-folder guard uses `parent.isEmpty`, so it also covers a share root.

## Alternatives and Why Not?

- Parse every UNC form only when `IOPath.isWindows`: that var is set lazily when FileSystem first initializes, so `parse` would depend on initialization order for the usual `\\server\share` spelling, and it couldn't be tested off Windows.
- Parse `//a/b` as UNC everywhere: it breaks `relativeTo` against the listed children of such a root on Linux and macOS (found in review).
- Add a `host` field to the case class: this changes the public API for a rare case.
- Store the root as `\\server\share`: the other segments use `/` internally, and `posixPath` would need a conversion step.
