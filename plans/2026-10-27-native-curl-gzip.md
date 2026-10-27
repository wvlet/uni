# Native HTTP client: decode gzip responses (#769)

## Goals

- Make the Scala Native HTTP client (`CurlChannel`) return decoded bodies when a server answers with
  `Content-Encoding: gzip` or `deflate`, as the JVM client already does.
- Remove the `Accept-Encoding: identity` workaround from `examples/cli-tool`.

## Background

- `CurlChannel` adds a raw `Accept-Encoding: gzip, deflate` header when the request has none.
  libcurl decodes only when `CURLOPT_ACCEPT_ENCODING` is set, so with a raw header the server
  compresses and curl hands back the compressed bytes. `GET https://api.github.com/repos/wvlet/uni`
  then fails in `Weaver.fromJson` with `UnexpectedToken ... 0x1f`.
- The JVM client (`JavaHttpChannel.openBody`) decodes by the response's `Content-Encoding`,
  whatever the request sent.
- curl bindings go through `uni_curl_shim.c` (ADR 2026-07-06). `curl_easy_setopt_str` already
  routes through the shim's pointer setter, so adding an option needs no C change.

## Design

Lane: **Express**. The diagnosis is clear, the fix is small, and it touches no always-gate area.
(The launch prompt gave no merge authorization, so finalize asks before merging.)

- Add `CURLOPT_ACCEPT_ENCODING: CInt = 10102` to `CurlBindings`.
- In `CurlChannel`, replace the hand-added header with
  `curl_easy_setopt_str(curl, CURLOPT_ACCEPT_ENCODING, c"")`. An empty string means every encoding
  this libcurl build supports, and curl writes the matching `Accept-Encoding` header itself.
- Caller-supplied `Accept-Encoding` (for example `identity`): set the option anyway. Custom headers
  replace curl's built-in one, so the caller's value is what goes on the wire, and curl still
  decodes whatever encoding the server picks. This matches the JVM client, which always decodes by
  `Content-Encoding`.
- Test: in `NativeCurlClientTest`, a `NativeServer` handler returns `Gzip.compress(...)` bytes with
  `Content-Encoding: gzip`. Assert the decoded text, once with no request header and once with an
  explicit `Accept-Encoding: gzip`.
- Example: drop the `.addHeader(HttpHeader.AcceptEncoding, "identity")` call, its comment, and the
  `HttpHeader` import from `GitHubCli.scala`, plus the #769 paragraph in its README. Verify with
  `exampleCliNative/nativeLink` and a live `repo wvlet/uni` run.

## Alternatives and Why Not?

- **Decode in Scala using `Content-Encoding`** (like the JVM client): duplicates work libcurl
  already does natively, and Native's `Gzip` path would be slower than zlib inside curl.
- **Skip `CURLOPT_ACCEPT_ENCODING` when the caller sets the header**: a caller sending
  `Accept-Encoding: gzip` would get raw gzip back again, which is the current bug.
