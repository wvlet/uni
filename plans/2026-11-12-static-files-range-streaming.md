# StaticFiles: Range requests and streamed reads

## Goals

- Let browsers seek in video and audio, and resume interrupted downloads, by answering `Range` with `206 Partial Content`.
- Stop reading each file whole into memory per request, so a large file costs a bounded buffer, not its size.

## Background

- `StaticFiles` (#854) reads every file with `readBytes` and returns `HttpContent.bytes`. #894 added a strong ETag from lstat (size, mtime) so it can act as the `If-Range` validator.
- Server responses can stream only SSE today (`Response.events`). Every other body is a byte array that each backend (Netty, Node, Native) writes in one go.
- Safari won't play `<video>` from a server that ignores `Range`, and Chrome can't seek.
- `HttpContent` is linked into browser bundles, so it must not import Node's `fs` statically (see the static-files ADR).

## Design

Lane: Standard — new public `HttpContent` case, new response behaviour, and changes to all three server backends.

- **A file-backed body, `HttpContent.FileContent(path, offset, length, contentType)`.** It names a byte range of a file instead of holding its bytes. `StaticFiles` returns it for every file, full or partial.
- **Each backend streams it natively, with a fixed `Content-Length`.**
  - Netty: `ChunkedFile` through the existing `ChunkedWriteHandler`.
  - Node: `fs.createReadStream({start, end}).pipe(res)`.
  - Native: an `InputStream` read in 64 KiB chunks.
- **Other consumers still get bytes.** `asBytes` / `toContentBytes` read the range on demand, so filters, the Electron RPC server, and in-process tests keep working unchanged. On Node the read loads `fs` at run time, as `StaticFilesCompat` does.
- **A single byte range only.** `bytes=a-b`, `bytes=a-`, and `bytes=-n` give 206 with `Content-Range`. Several ranges, another unit, or bad syntax are ignored and get the full 200, which RFC 9110 allows; `multipart/byteranges` is not worth its complexity for media seeking.
- **Unsatisfiable range → `416` with `Content-Range: bytes */<size>`.** It applies only to a file that is servable, so it reveals nothing beyond what a 200 would.
- **`If-Range` matches the strong ETag exactly.** A date or any other value counts as a mismatch and gets the full 200, since no `Last-Modified` is sent.
- **Precedence:** a matching `If-None-Match` still gives 304 before `Range` is looked at. Every file response adds `Accept-Ranges: bytes`. A 206 keeps ETag, Cache-Control, and `nosniff`.
- **No compression on 206.** Netty's compressor must pass a partial response through, because `Content-Range` counts identity bytes. A 200 is compressed as today.
- **A file that shrinks while it is sent** can't meet its `Content-Length`, so the backend closes the connection. The client sees a truncated response and retries.
- `HEAD` stays out of scope, as the ADR records.
- Tests cover Range parsing, 206/416/200 selection, `If-Range`, the streamed body on each backend (JVM/Netty, Node, Native), and lazy `toContentBytes`. The ADR and server docs are updated.

## Alternatives and Why Not?

- **A generic streamed body (`Rx[Array[Byte]]`).** Rx has no backpressure, so a fast file source would buffer whole files again on a slow client.
- **Stream only files above a size threshold.** Two code paths for no gain: small files stream in one chunk anyway.
- **`multipart/byteranges` for several ranges.** Browsers and media players ask for one range; full 200 is a valid answer.
