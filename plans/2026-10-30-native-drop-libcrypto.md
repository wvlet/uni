# Drop libcrypto from Scala Native

## Goals

- A Uni Scala Native binary (e.g. NativeServer + RPCDispatcher) should depend only on libc / libSystem, not on OpenSSL's libcrypto.
- Unblocks running on macOS without Homebrew OpenSSL and minimal one-binary Linux containers, ahead of the "typed RPC without the JVM" post.

## Background

- `NativeSecureRandom` (uni-core, Native) has `@link("crypto")` and calls only `RAND_bytes`; the MD5/SHA externs are unused.
- It backs `compat.random` → `SecureRandom.getInstance`, used by WebSocketFrame mask keys, ULID, UUIDv7, NanoId, so every server reaches it.
- Native CI runs on Ubuntu (glibc) and Windows (MSVC/clang). `getentropy` does not exist on Windows.
- The existing check `RAND_bytes(...) < 0` misses the documented failure value 0.

## Design

- Lane: Standard, pre-approved by the launch prompt (it carries the diagnosis and design); no merge without the user's approval.
- POSIX (macOS, Linux glibc/musl): call libc `getentropy` in chunks of at most 256 bytes; retry on EINTR; throw `IllegalStateException` on any other failure.
- Windows: call `BCryptGenRandom` with `BCRYPT_USE_SYSTEM_PREFERRED_RNG` from `bcrypt` (a system library).
- Select the platform with `LinktimeInfo.isWindows`, so the Windows extern (and its `@link("bcrypt")`) is dropped from POSIX binaries at link time.
- Stay pure Scala: no new C shim in uni-core.
- Remove `@link("crypto")` and all OpenSSL externs. Little code from scala-native-crypto remains (the `next(numBits)` part comes from Scala.js), so keep only the Scala.js attribution.
- Tests (Native): byte arrays of sizes 0, 1, 255, 256, 257, 4096 are filled; consecutive calls differ; ULID/UUIDv7/NanoId still generate.
- Docs: no OpenSSL-for-Native install notes exist today (the `libcurl4-openssl-dev` lines are curl's package name). Keep the Windows CI's `crypto.lib` mapping, since Scala Native's own javalib may still request it.

## Alternatives and Why Not?

- `arc4random_buf` (macOS) / `getrandom` (Linux): two POSIX code paths instead of one; `getentropy` covers both.
- A C shim with `#ifdef _WIN32`: works, but adds C to uni-core, which is currently pure Scala.
- Reading `/dev/urandom`: needs file handling and fails in chroot/minimal images without `/dev`.
