# Add PermSet type and POSIX file permission support

## Summary

Add `PermSet` type and POSIX file permission support to `wvlet.uni.io`, following os-lib's patterns but adapted for Uni's cross-platform design (JVM + JS + Native).

## Design

### PermSet type (`uni-core/src/main/scala/wvlet/uni/io/PermSet.scala`)
- Immutable `case class PermSet(bits: Int)` wrapping 9-bit POSIX permission value
- Construction: `PermSet(0x1ed)` (octal bits), `PermSet("rwxr-xr-x")` (string), `PermSet.fromOctalString("755")`
- Individual permission queries: `ownerRead`, `ownerWrite`, `ownerExecute`, etc.
- Set operations: `|` (union), `&` (intersection), `diff`
- Formatting: `toPermString` ("rwxr-xr-x"), `toOctalString` ("755")
- Constants: `PermSet.empty`, `PermSet.all`

### FileSystem API additions
- `permissions(path: IOPath): PermSet` — get POSIX permissions
- `setPermissions(path: IOPath, permissions: PermSet): Unit` — set POSIX permissions
- `owner(path: IOPath): String` — get file owner name (JVM + Native only)
- `group(path: IOPath): String` — get file group name (JVM + Native only)

### FileInfo additions
- `permissions: Option[PermSet]` — POSIX permissions (None on unsupported platforms)
- `owner: Option[String]` — file owner name
- `group: Option[String]` — file group name

### Platform support
| Feature | JVM | Native | JS (Node) | JS (Browser) |
|---------|-----|--------|-----------|--------------|
| permissions | java.nio PosixFilePermissions | posix stat/chmod | fs.statSync/chmodSync | UnsupportedOp |
| owner | Files.getOwner | getpwuid | UnsupportedOp | UnsupportedOp |
| group | PosixFileAttributes.group | getgrgid | UnsupportedOp | UnsupportedOp |

## Files changed
- **New**: `uni-core/src/main/scala/wvlet/uni/io/PermSet.scala`
- **Modified**: `FileInfo.scala`, `FileSystem.scala`, `IO.scala` (shared)
- **Modified**: `FileSystemImpl.scala` (JVM, JS, Native)
- **Modified**: `NodeFS.scala` (JS — added `chmodSync`)
- **New tests**: `PermSetTest.scala`, `PermissionsTest.scala`
