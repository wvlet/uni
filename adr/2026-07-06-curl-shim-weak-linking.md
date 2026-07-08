# 2026-07-06: `uni_curl_shim.c` resolves libcurl symbols via `dlsym`, not `extern`

Issue: https://github.com/wvlet/uni/issues/622

## Context

Scala Native cannot reliably call libcurl's variadic `curl_easy_setopt` /
`curl_easy_getinfo` — see the file header in `uni_curl_shim.c` and the
`CurlBindings.Extern` docstring. PR #580 (v2026.1.13) fixed that by routing
through fixed-arity C wrappers compiled from
`uni/.native/src/main/resources/scala-native/uni_curl_shim.c`, letting the C
compiler emit the correct variadic calling convention at the shim's own call
site.

The regression #622 reports: any Scala Native project pulling in
`uni_native0.5_3-2026.1.13` and later — even one whose Scala code only uses
`LogSupport` — fails to link with

    ld: uni_curl_shim.c.o: undefined reference to `curl_easy_setopt'
    ld: uni_curl_shim.c.o: undefined reference to `curl_easy_getinfo'

Why: Scala Native's linker compiles **every** `.c` file under
`resources/scala-native/` in **every** jar on the classpath, unconditionally.
The v2026.1.13 shim declared `extern int curl_easy_setopt(...)`, so its `.o`
carried a strong undefined reference. `-lcurl` reaches the linker only through
`@link("curl")` on `CurlBindings.Extern`; when Scala DCE removes `CurlBindings`
(because nothing references it), that link option never propagates, and the
undefined reference in the always-compiled `.o` blows up the final link. The
`-lcurl` in this repo's own `build.sbt` only helps this build — downstream
projects don't see it. The shim can't sit in a "curl users only" sub-module
either: Scala Native has no jar-level knob to conditionally include a resource
`.c`.

## Decision

Resolve the libcurl symbols lazily, at first call, from whatever is already
loaded into the process — not via C-level `extern` — inside a run-once
initialiser that stores each pointer in a `static` cache. The shim's `.o` then
has zero references to libcurl symbols; downstream projects that don't use
CurlBindings link cleanly. Projects that do use CurlBindings still pull libcurl
in via `@link("curl")` on `CurlBindings.Extern`, and the lookup finds the
symbols inside the already-loaded libcurl at runtime.

The two primitives this needs — a lookup over all loaded modules, and a
thread-safe run-once — are spelled differently per platform, so the file carries
a `#if defined(_WIN32)` split:

| | POSIX | Windows |
|---|---|---|
| lookup | `dlsym(RTLD_DEFAULT, name)` | `GetProcAddress` over `EnumProcessModules` |
| run-once | `pthread_once` | `InitOnceExecuteOnce` |

The Windows half exists because MSVC ships neither `<dlfcn.h>` nor
`<pthread.h>`; the first version of this shim (v2026.1.17) included both
unconditionally and broke every downstream Windows Scala Native build with
`fatal error: 'dlfcn.h' file not found`.

The function-pointer typedefs are declared variadic
(`typedef int (*fn)(void *, int, ...)`) so the call at the shim's own call site
still emits the correct variadic calling convention — that's what fixes the
original CURLE_URL_MALFORMAT bug from #580, and it works exactly the same via a
variadic function-pointer as via a variadic extern.

If the lookup returns NULL (the shim is called from a build that somehow got the
wrappers linked in without libcurl present), the shim prints a pointer to #622
on stderr and `abort()`s rather than jumping to NULL.

Two CI additions guard this, because the file's two failure modes need two
different checks:

- **`curl_shim_c_windows`** ("curl shim C (Windows)") compiles the file standalone
  with clang on `windows-latest`. Nothing else here compiles the Windows half of
  the `#if`, which is how v2026.1.17's POSIX-only `#include <dlfcn.h>` shipped. It
  runs on every push to `main` and on pull requests touching native code (the
  `native` paths-filter): a `.c` file, anything under a `.native/` source tree, or
  the Scala Native version in `project/`.
- **`.github/scripts/check-curl-shim.sh`**, a step in the Linux Native job, which
  compiles the shim standalone and asserts via `nm -u` that the object names no
  `curl_easy_*` symbol. No Scala Native job can catch that regression on any OS:
  `build.sbt` passes `-lcurl` unconditionally, so uni's own binaries always
  resolve those symbols. Only a consumer that doesn't link libcurl breaks — the
  object inspection stands in for that consumer.

## Non-obvious points a future reader would otherwise reverse-engineer

### `weak_import` / `__attribute__((weak))` on the extern is *not* a substitute

The obvious alternative — mark the `extern` declarations weak so the linker
tolerates the undefined — appears to work on Linux but **fails on macOS**. macOS
`ld` still refuses to leave a weak-undefined symbol unresolved at final link
time unless the binary is linked with `-undefined dynamic_lookup` (or the
per-symbol `-U <sym>`). That flag can't be forced on downstream binaries from a
jar-resource `.c` file, so the trick is unusable here. `dlsym` sidesteps the
whole problem because the `.o` carries no libcurl symbol at all — only libc
symbols (`dlsym`, `fprintf`, `abort`, `___stderrp`), which are guaranteed
available.

### Why `RTLD_DEFAULT` — not `dlopen("libcurl", ...)`

`RTLD_DEFAULT` walks the symbols already loaded into the process. That's the
right thing here: consumers that use CurlBindings get libcurl loaded via
`@link("curl")`, and any curl functions Scala calls directly (e.g.
`Extern.easyInit`) must resolve to the *same* libcurl that the shim uses —
otherwise CURL handles would be exchanged between two different libcurl
instances. `dlopen("libcurl.so.4", ...)` would risk loading a second copy.

### The `-ldl` / `-lpthread` question

`dlsym` lives in `<dlfcn.h>`, `pthread_once` in `<pthread.h>`. On macOS both are
in libSystem (always linked). On modern Linux (glibc 2.34+, released 2021)
`libdl` and `libpthread` are merged into `libc`; on older glibc they were
separate — but Scala Native's own `nativelib` already links both, so the shim
inherits them. No extra linker option is required from consumers. Verified by
inspecting Scala Native's link line (`[pthread, dl, m, crypto, curl, z]`).

### A real "Scala Native on Windows" CI job would be better, and is not possible yet

The obvious guard — build and run the native test suite on Windows, which would
cover this break and any future one — was tried and abandoned. It gets the whole
toolchain up (LLVM, vcpkg libcurl/zlib/OpenSSL, aliasing each `foo.lib` that
`-lfoo` asks for), compiles every source *including this shim*, and then fails at
the final link:

    error LNK2019: unresolved external symbol scalanative_pollin
      referenced in function ...wvlet.uni.http.NativeServerTest...
    fatal error LNK1120: 5 unresolved externals

`NativeServer` uses POSIX `poll()`, and Scala Native's `posixlib/poll.c` is wrapped
in `#if defined(__unix__) || (defined(__APPLE__) && defined(__MACH__))` — the
symbols do not exist on Windows. Every native HTTP test spins up a server, so uni's
native test binary cannot link there at all. Giving `NativeServer` a Windows path
(`WSAPoll`) would unblock it; until then, compiling the shim standalone is the
Windows coverage available. Notably, that abandoned run *did* prove the shim
compiles and links clean under clang/MSVC: none of the unresolved symbols were
`curl_easy_*`.

### Windows: why module enumeration, and why `PSAPI_VERSION 2`

Windows has no `RTLD_DEFAULT`. `GetProcAddress` takes one `HMODULE` at a time,
so the shim asks `EnumProcessModules` for every module loaded into the process
and walks them in order — the process image comes first, matching `dlsym`'s
search order. `GetModuleHandleA(NULL)` alone would only see the executable's own
exports and would miss `libcurl.dll`.

`#define PSAPI_VERSION 2` before `<psapi.h>` redirects `EnumProcessModules` to
`K32EnumProcessModules`, which lives in `kernel32.dll` and so is always linked.
Under the default (version 1) the symbol resolves out of `psapi.lib`, and a
jar-resource `.c` file cannot make downstream binaries pass an extra linker
flag — the exact constraint that kills the weak-symbol approach on macOS.
`_WIN32_WINNT` is floored at `0x0600` for `InitOnceExecuteOnce` (Vista+), for
MinGW header sets that leave it unset.

`GetProcAddress` returns `FARPROC`; casting it straight to `void *` trips
`-Wcast-function-type`, so the shim rounds it through `uintptr_t`.

### Windows: libcurl must be a DLL, not a static `.lib`

Runtime symbol lookup can only find *exported* symbols. A statically linked
libcurl (vcpkg's `*-windows-static` triplets) exports nothing, so
`GetProcAddress` returns NULL and the shim aborts with its diagnostic. Consumers
must link libcurl dynamically — vcpkg's default `x64-windows` / `arm64-windows`
triplets do exactly that, producing `libcurl.lib` as an import library for
`libcurl.dll`.

This is not a Windows quirk so much as the price of the whole approach: a static
libcurl on Linux (`libcurl.a`, no `--export-dynamic`) is invisible to
`dlsym(RTLD_DEFAULT, ...)` in precisely the same way. The old `extern`-based shim
worked with static libcurl on both — that capability is what was traded away to
fix #622.

### `_GNU_SOURCE` is required on glibc

`RTLD_DEFAULT` is a GNU extension: glibc's `<dlfcn.h>` only defines it when
`_GNU_SOURCE` is set. macOS libSystem exposes it unconditionally, so the macro
is harmless there. The file defines `_GNU_SOURCE` at the top — dropping it
would silently break Linux builds with `error: 'RTLD_DEFAULT' undeclared`.

### Lazy init needs a run-once guard, not "same value written twice"

An earlier draft argued no lock was needed because racing threads would race to
write the same pointer value. That reasoning is wrong under C11: concurrent
unsynchronised accesses to a non-atomic object where at least one is a write is
a data race, i.e. undefined behaviour, regardless of the value written. The
compiler is entitled to assume no such race exists and to re-order/eliminate
the loads and stores accordingly. `pthread_once` (and `InitOnceExecuteOnce` on
Windows) gives a proper happens-before edge between the resolver's writes and
every subsequent reader, at negligible cost after the first call (a plain
"already done" flag check).

### The variadic-typedef trick is the whole variadic story

The C ABI's variadic calling convention is determined by the *type at the call
site*, not by whatever the symbol on the other end was compiled as. That is why
calling through `uni_curl_setopt_fn` (declared variadic) reproduces #580's fix
even though the pointer was obtained from `dlsym` / `GetProcAddress` (neither of
which carries type information). Do not "simplify" the typedef to a fixed-arity
signature — that reintroduces the CURLE_URL_MALFORMAT bug from #580.

## Consequences

- Downstream Scala Native projects that don't use CurlBindings link cleanly
  again — #622 fixed.
- Downstream projects that do use CurlBindings pay one symbol lookup per unique
  wrapper on first call (two lookups total across the process's lifetime); every
  subsequent call is a plain indirect function call. On Windows a lookup also
  walks the loaded-module list, which is still a once-per-process cost.
- CurlBindings' `@link("curl")` remains load-bearing — it's how libcurl actually
  gets into the process. Do not remove it.
- libcurl must be linked dynamically. Statically linked libcurl no longer works
  on any platform, Windows included; see above.
- Removing the shim `.c` file, or any of its includes, would regress either the
  ABI fix, the link-error fix, or the Windows build; keep all three. The
  "Scala Native (Windows)" job and `check-curl-shim.sh` are what notice.
