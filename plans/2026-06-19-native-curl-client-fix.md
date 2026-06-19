# Fix the Native libcurl client (CURLE_URL_MALFORMAT)

## Context
The Scala Native HTTP client (`CurlChannel`) failed on every request with `CURLE_URL_MALFORMAT`
(code 3) on a valid loopback URL — so it was unusable (prior work fell back to a raw POSIX client to
test the Native server).

## Root cause
`curl_easy_setopt` and `curl_easy_getinfo` are **C variadic** functions. The bindings called them via
**fixed-arity** `@extern` declarations. On arm64-apple-darwin the variadic argument is passed on the
stack while a fixed-arity call places it in a register, so curl read garbage for the value — a valid
URL became "malformed". (`setopt` returns OK because it just stores the pointer; `perform` then fails.)
A `CVarArg*` variadic extern did **not** fix it either — Scala Native's variadic codegen does not pass
the argument through for this function on this toolchain (verified: even a global `c"..."` literal URL
still produced code 3).

## Fix
Route `setopt`/`getinfo` through tiny **fixed-arity C shims** that forward to the real variadic
functions, so the C compiler emits the correct variadic call and Scala Native sees ordinary
fixed-arity symbols:
- `uni/.native/src/main/resources/scala-native/uni_curl_shim.c` — `uni_curl_easy_setopt_ptr/_long`
  and `uni_curl_easy_getinfo_ptr`, forwarding to `curl_easy_setopt`/`curl_easy_getinfo`. Declares the
  curl prototypes locally so no libcurl headers are needed at build time (symbols resolve from -lcurl).
- `CurlBindings`: the `Extern` object declares the three shim functions; the `curl_easy_setopt_*` /
  `curl_easy_getinfo_long` wrappers call them (pointer options, the write/header callbacks via
  `CFuncPtr.toPtr`, slist and the response-code out-pointer all go through the `_ptr` shim).

## Verification
New `NativeCurlClientTest` hits the in-process `NativeServer` over loopback: GET (URL + write callback
+ response code), POST (POSTFIELDS), and header round-trip (HTTPHEADER slist) — all 200. `uniNative/test`:
1402 tests, 0 failed.

## Follow-ups (remaining)
Cross-platform WebSocket client; WS ping/pong heartbeat; permessage-deflate.
