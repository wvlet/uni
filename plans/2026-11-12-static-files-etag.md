# StaticFiles: ETag and Cache-Control

## Goals

- Let browsers revalidate files served by `StaticFiles` and get `304 Not Modified` instead of the whole file again.
- Send an explicit `Cache-Control`, so caching does not depend on browser heuristics and a new build is picked up on the next load.

## Background

- `StaticFiles` (#854, `adr/2026-11-04-static-files.md`) reads each file whole and sends no validator or cache header. Every reload re-transfers the full bundle.
- The sibling item (Range requests and streaming) will stop reading the whole file into memory. `If-Range` there needs a strong validator, and a streamed response must not require reading the file first.
- Every platform's lstat already returns size and mtime (`FileInfo` on JVM/Native, `fs.lstatSync` on Node).

## Design

Lane: Standard — new default response headers and new config methods on a public API.

- **ETag from mtime and size, not a content hash.** A strong ETag such as `"1a2b-18f3c4d5e6"` (size and mtime in ms, hex), as nginx does. It costs one lstat, which `findFile` already does, so a 304 never reads the file, and it keeps working once Range/streaming lands.
- **`If-None-Match` answers 304.** Weak comparison per RFC 9110: `*` or any listed tag (with or without `W/`) matches. The 304 carries `ETag`, `Cache-Control`, and `nosniff`, and no body.
- **Default `Cache-Control: no-cache`.** The browser may cache but must revalidate each time, so cheap 304s and never a stale `index.html` after a deploy. `withCacheControl(value)` overrides it and `noCacheControl()` sends none.
- **One policy for all files.** Hashed assets would benefit from `immutable`, but a per-path policy needs a design of its own; the setting is one value for now.
- **No `Last-Modified` / `If-Modified-Since`.** With an ETag on every response, browsers revalidate with `If-None-Match`, and this avoids a hand-written HTTP-date formatter for Scala.js.
- **SPA fallback** gets the same headers and conditional handling as a direct request for the index file.
- Tests cover the ETag format and change on rewrite, 304 for matching / `*` / weak / listed tags, 200 for a mismatch, and the Cache-Control settings. The ADR and server docs are updated.

## Alternatives and Why Not?

- **Content hash (SHA-256 of the bytes).** Needs the whole file read per request, even for a 304, which is what streaming is meant to remove.
- **Weak ETag from mtime and size.** Equally cheap, but `If-Range` needs a strong validator.
- **`max-age` default.** Users would see stale `index.html` after a deploy until it expires.
