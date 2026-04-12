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

## Design decisions from review

- **Named constants**: Extracted `0x1ff` to `PermSet.PermissionMask` and added `PermSet.AnyExecute` for the execute-bit mask. Replaced all magic numbers across platform implementations.
- **JVM single read**: `info()` reads `PosixFileAttributes` first (extends `BasicFileAttributes`), falling back to `BasicFileAttributes` on non-POSIX filesystems. This avoids a double filesystem read. Non-POSIX fallback still tries `Files.getOwner()` for owner info.
- **JVM ordinal mapping**: `posixPermsToPermSet`/`permSetToPosixPerms` use ordinal-to-bit mapping instead of 18 if-statements.
- **Native lstat for symlinks**: Symlink branch in `info()` uses `lstat` (via `lstatFileInfo`) to get link metadata, not target metadata.
- **Native reentrant API**: Scala Native 0.5 uses reentrant `getpwuid(uid, buf)` and `getgrgid(gid, buf)` which write to a provided buffer. No `grpOps` exists, so group name is accessed via raw struct field `_1`.
- **4-digit octal**: `fromOctalString` accepts 1-4 digit strings (e.g., "0755", "4755"), masking off special bits.

## Files changed
- **New**: `uni-core/src/main/scala/wvlet/uni/io/PermSet.scala`
- **Modified**: `FileInfo.scala`, `FileSystem.scala`, `IO.scala` (shared)
- **Modified**: `FileSystemImpl.scala` (JVM, JS, Native)
- **Modified**: `NodeFS.scala` (JS — added `chmodSync`)
- **New tests**: `PermSetTest.scala`, `PermissionsTest.scala`
