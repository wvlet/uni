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

Resolve the libcurl symbols lazily via
`dlsym(RTLD_DEFAULT, "curl_easy_setopt")` — not via C-level `extern` — inside a
`pthread_once` initialiser that stores each pointer in a `static` cache. The
shim's `.o` then has zero references to libcurl symbols; downstream projects
that don't use CurlBindings link cleanly. Projects that do use CurlBindings
still pull libcurl in via `@link("curl")` on `CurlBindings.Extern`, and
`RTLD_DEFAULT` finds the symbols inside the already-loaded libcurl at runtime.

The function-pointer typedefs are declared variadic
(`typedef int (*fn)(void *, int, ...)`) so the call at the shim's own call site
still emits the correct variadic calling convention — that's what fixes the
original CURLE_URL_MALFORMAT bug from #580, and it works exactly the same via a
variadic function-pointer as via a variadic extern.

If `dlsym` returns NULL (the shim is called from a build that somehow got the
wrappers linked in without libcurl present), the shim prints a pointer to #622
on stderr and `abort()`s rather than jumping to NULL.

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

### `_GNU_SOURCE` is required on glibc

`RTLD_DEFAULT` is a GNU extension: glibc's `<dlfcn.h>` only defines it when
`_GNU_SOURCE` is set. macOS libSystem exposes it unconditionally, so the macro
is harmless there. The file defines `_GNU_SOURCE` at the top — dropping it
would silently break Linux builds with `error: 'RTLD_DEFAULT' undeclared`.

### Lazy init needs `pthread_once`, not "same value written twice"

An earlier draft argued no lock was needed because racing threads would race to
write the same pointer value. That reasoning is wrong under C11: concurrent
unsynchronised accesses to a non-atomic object where at least one is a write is
a data race, i.e. undefined behaviour, regardless of the value written. The
compiler is entitled to assume no such race exists and to re-order/eliminate
the loads and stores accordingly. `pthread_once` gives a proper
happens-before edge between the resolver's writes and every subsequent reader,
at negligible cost after the first call (a plain "already done" flag check).

### The variadic-typedef trick is the whole variadic story

The C ABI's variadic calling convention is determined by the *type at the call
site*, not by whatever the symbol on the other end was compiled as. That is why
calling through `uni_curl_setopt_fn` (declared variadic) reproduces #580's fix
even though the pointer was obtained from `dlsym` (which has no type
information). Do not "simplify" the typedef to a fixed-arity signature — that
reintroduces the CURLE_URL_MALFORMAT bug from #580.

## Consequences

- Downstream Scala Native projects that don't use CurlBindings link cleanly
  again — #622 fixed.
- Downstream projects that do use CurlBindings pay one `dlsym` per unique
  wrapper on first call (three lookups total across the process's lifetime);
  every subsequent call is a plain indirect function call.
- CurlBindings' `@link("curl")` remains load-bearing — it's how libcurl actually
  gets into the process. Do not remove it.
- Removing the shim `.c` file, or any `dlfcn`/`stdio` include, would regress
  either the ABI fix or the link-error fix; keep both.
