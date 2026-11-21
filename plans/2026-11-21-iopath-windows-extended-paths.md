# IOPath: Windows extended-length and device paths

## Goals
- `IOPath.parse("\\?\C:\data\x.parquet")` gives the same path as `C:\data\x.parquet`, so `path`, `parent`, `root`, `normalize`, `resolve`, `relativeTo`, and `startsWith` work on it.
- `\\?\UNC\server\share\dir` gives the same path as `\\server\share\dir`.
- Device paths that IOPath cannot represent fail with a clear error instead of parsing into a wrong path.

## Background
- `parse` turns every `\` into `/` and splits, so `\\?\C:\x` becomes segments `?`, `C:`, `x` and renders as `\?\C:\x` on Windows.
- Windows APIs and user input can produce these forms; Java's `Paths.get` on Windows rejects `?` in a path, so keeping the prefix would break JVM file access anyway.
- The JVM, Node.js, and the Win32 layer add the long-path prefix themselves where they need it.
- Another session is adding UNC support (`\\server\share`); `\\?\UNC\` should reuse whatever representation it chooses.

## Design
- Lane: Express — a bug fix with a clear diagnosis, no new public API, behaviour changes only for `\\?\` and `\\.\` inputs. The launch prompt says not to merge, so the PR is held for the user.
- `parse` strips a leading `\\?\` or `\\.\` when a drive (`C:`) follows, and rewrites `\\?\UNC\` / `\\.\UNC\` to `\\` before parsing the rest. The prefix is not kept: `path` renders `C:\x`.
- Other prefixed forms (`\\?\Volume{...}\`, `\\.\pipe\x`, `\\.\COM1`, `\\?\GLOBALROOT\...`) throw `IllegalArgumentException` naming the path.
- Only the backslash form counts as a prefix; `//?/x` and `//./x` stay ordinary POSIX paths.
- `Parquet.dataset` on Windows strips the prefix before splitting globs, since `?` is a glob character there.
- `\\?\UNC\` gets full UNC behaviour once the UNC PR lands; until then it parses exactly like `\\server\share`. Tests assert that equivalence, so they hold either way.
- Tests cover parse and the path methods on all platforms, and `path` only on Windows.

## Alternatives and Why Not?
- Keep `\\?\` in the root segment so `path` round-trips it: `Paths.get` on Windows rejects it, and every method that inspects the drive would need a second case.
- Add a public flag or method for the prefix: new API for a need no caller has shown.
- Strip `\\.\` device paths silently: `\\.\pipe\x` would become the file `\pipe\x`.
