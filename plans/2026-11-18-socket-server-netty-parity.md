# Bring the built-in socket server up to Netty's HTTP/1.1 defenses and features

## Goals

- Port the request-side safeguards and HTTP/1.1 behaviors that uni-netty gets from Netty's pipeline into the shared socket server (`uni/.jvm-native`), so the JVM built-in server and Scala Native are as safe as Netty to expose.
- Close real attack paths first: slow-request (slowloris) holds, request smuggling through lenient parsing, and unbounded work per request.
- Remove the visible feature gaps that make ordinary clients slower or fail: `Expect: 100-continue`, chunked request bodies, compressed request bodies, HTTP/1.0 keep-alive.
- Keep throughput: the `examples/rpc-native/bench` run (JVM built-in vs Netty) must not regress, and the PR reports its numbers.

## Background

- uni-netty's pipeline: `HttpServerCodec(maxInitialLineLength 4096, maxHeaderSize 8192)` → `RequestReadTimeoutHandler(30s)` → `HttpServerKeepAliveHandler` → bounded request decompression (64 MiB) → `HttpObjectAggregator(maxContentLength 64 KiB)` → response compressor → `HttpServerExpectContinueHandler`.
- The built-in reader (`HttpConnectionReader`) has one limit, `maxRequestSize` (1 MiB by default), for headers and body together. It does not check the HTTP version. Header lines without a colon are skipped silently, and whitespace before the colon is trimmed.
- Gaps confirmed in the code:
  - **Slowloris:** each read waits up to 30 s, but nothing bounds the time a whole request takes. One byte every 29 s holds a connection forever. On Native that means one of 16 workers, so 16 slow clients stop the server.
  - **Smuggling:** `Content-Length: +5` parses as 5 (`toIntOption`). Header names are not validated, and whitespace before the colon is accepted. A bare CR or LF inside a header value is kept, and obs-fold lines are treated as new headers. Netty rejects each of these, or normalizes it. Duplicate `Host` headers are also accepted; Netty does not check that, but RFC 9112 §3.2 requires a 400.
  - **Limits:** a 1 MiB header block is accepted. Netty stops at 8 KiB of headers and a 4 KiB request line. An oversized body gets 400 (Netty: 413), and an unknown method gets 400 (uni-netty: 501).
  - **Response side:** a handler-set `Transfer-Encoding` is copied next to the server's own `Content-Length`, which confuses response framing. `Connection` is compared as a whole string, so `Connection: keep-alive, close` keeps the connection open. Writes have no timeout, so a client that never reads pins a Native worker or a JVM virtual thread forever.
  - **WebSocket:** text frames and close codes are not checked for UTF-8. Netty's `Utf8FrameValidator` closes with 1007.
  - **Features:** chunked request bodies get 400. `Expect: 100-continue` gets no interim response, so curl waits 1 s before sending any body over 1 KiB. HTTP/1.0 requests stay keep-alive unless they send `Connection: close`; HTTP/1.0 should close by default unless the request asks for keep-alive. Compressed request bodies reach the handler undecoded.
- No concurrent-connection cap exists in Netty or uni-netty either. Only Native is bounded, by its worker pool.
- ADR `2026-11-18-jvm-builtin-http-server.md`: the JVM built-in server has no backend options and uses fixed defaults. Native has `NativeServerConfig`.
- ADR `2026-11-18-lean-specs.md`: the framing rules live in `spec/lean/UniSpec/HttpReader.lean`, and `HttpReaderSpecTest` replays the generated cases.

## Design

Lane: Standard. This changes the security boundary (request parsing, limits) and the server's behavior visible to clients, on JVM and Native.

- **Separate limits, as Netty sets them.** Request line at most 4 KiB → 414. Header block at most 8 KiB → 431. Body at most `maxContentLength` → 413 and close. The body default stays 1 MiB, matching Native and Node.js (ADR secure-defaults), not Netty's 64 KiB. Native gets `withMaxInitialLineLength`/`withMaxHeaderSize` on `NativeServerConfig` and `Native.options`; `maxRequestSize` becomes the body limit. The JVM built-in server uses fixed defaults.
- **Request-read deadline, as `RequestReadTimeoutHandler` does.** The idle timeout still covers the wait for a new request. Once its first byte arrives, the whole request, headers and body, must arrive within 30 s, or the connection closes. Every read waits only for the time left. The handler's own time is not counted. An upgraded WebSocket connection is not affected.
- **Strict request parsing, to block smuggling.** Each of these gets 400 and closes the connection:
  - a header name that is not an RFC 9110 token, or whitespace before the colon
  - obs-fold continuation lines, and a CR, LF, NUL, or other control character inside a header value
  - a bare CR or LF in the head
  - a request-target with control characters or spaces, or a request line without an `HTTP/1.x` version (505 for other versions)
  - a `Content-Length` with anything other than digits, or more than one value, even identical ones (Netty 4.2: `allowDuplicateContentLengths` false)
  - more than one `Host` header (RFC 9112 §3.2). A request without `Host` stays allowed, as ADR secure-defaults records
  - an unknown method gets 501, as in uni-netty, before the body is read
- **Chunked request bodies.** `Transfer-Encoding: chunked` is decoded:
  - Chunk sizes are strict hex with an overflow guard. Chunk extensions are skipped.
  - Trailers count toward the header limit and are dropped.
  - The decoded body counts toward `maxContentLength`.
  - The handler sees `Content-Length` and no `Transfer-Encoding`, like Netty's aggregator output.
  - A `Content-Length` sent next to `Transfer-Encoding` is ignored, and the connection closes after the response (RFC 9112 §6.1).
  - Any other transfer coding gets 501. `Transfer-Encoding` on HTTP/1.0 gets 400.
- **`Expect: 100-continue`.** After the headers, the server sends `100 Continue` before it reads the body. When the declared body is over the limit, it answers 413 and closes instead. Any other expectation gets 417.
- **Keep-alive as Netty's `HttpUtil.isKeepAlive`.** `Connection` is parsed as a list of tokens, and `close` anywhere closes. HTTP/1.0 keeps the connection only when the request sends `keep-alive`, and the response then says `keep-alive`.
- **Safe responses.** The writer manages `Transfer-Encoding` along with `Content-Length` and drops a handler-set one.
- **Write deadline.** A response write that makes no progress for 30 s closes the connection (`SO_SNDTIMEO` on Native, a timed write on the JVM). A slow reader then cannot pin a worker.
- **WebSocket UTF-8 validation.** Invalid UTF-8 in a text message or a close reason, or an invalid close code, closes with 1007 or 1002, as Netty does.
- **Bounded request decompression, on by default as in Netty.** gzip, deflate, and the x- aliases are decoded before the host check and filters, with a streaming inflater (uni-core's `Gzip` works on JVM and Native). It uses `java.util.zip.Inflater` directly, with a counting cap; uni-core's `Gzip` only decodes whole arrays. deflate accepts zlib-wrapped and raw data, as Netty's `ZLIB_OR_NONE` does. The decoded size is capped at 64 MiB → 413, and a malformed body gets 400; both close the connection. Other encodings pass through. On Native, `Inflater` needs zlib, so Native server binaries now link `-lz` (the user chose this), including the bench build. Native gets `Native.options.noRequestDecompression`/`withMaxDecompressedRequestSize`. The JVM built-in server uses the defaults, and the startup log line no longer says to add uni-netty for decompression.
- **Lean model.** `HttpReader.lean` replaces `chunked_rejected` and the loose `isChunked` ("contains chunked"); `chunked` must be the only coding. It also tightens the `conflicting_content_length` theorem to reject every repeated value. New rules:
  - chunked decoding consumes exactly its bytes, and the body is the concatenated chunk data
  - CL is ignored when TE is chunked
  - the `Content-Length` grammar is digits only
  - each header-validation rejection
  - Regenerate the cases with `lake exe gen-http-reader-cases`; the limits stay out of the model, as today.
- **Records.** A new ADR records these decisions, because they reverse lines in ADR 2026-11-18 ("chunked bodies are rejected", "does not decode compressed request bodies"). The PR updates `docs/http/server.md` and the plugin skill's `references/http-server.md`, and bumps the plugin version.
- **Response compression, as uni-netty's `IdentityRangeCompressor`.** gzip or deflate, chosen from `Accept-Encoding`, for compressible bodies. 206 bodies stay identity, and a strong ETag on a compressed body becomes weak. File, SSE, and already-encoded bodies are not compressed.
- **Out of this PR:** a cap on concurrent connections (Netty has none). On Native, document that slow clients can take every worker and point to `withWorkerThreads`. Also out: HTTP/2, TLS, and uni-netty changes.
- Tests cover each rejection and status code, chunked and trailer framing across read splits, 100-continue with curl-like clients, the deadline with a trickling client, and decompression limits, on JVM and Native. The PR reports the bench before and after.

## Alternatives and Why Not?

- **Adopt Netty's 64 KiB body default.** It would break Native and JVM built-in apps that accept uploads today, and Node.js uses 1 MiB.
- **Keep one combined `maxRequestSize`.** 1 MiB of headers is far more than any client needs, and it is what the header-parsing cost scales with.
- **Reject chunked bodies, as today.** Streaming clients (`curl -T -`, Java `HttpClient` with an InputStream body, fetch with a stream) cannot call the server.
- **Lenient parsing with normalization (obs-fold → SP).** RFC 9112 allows it, but rejecting is simpler to state in Lean and leaves nothing a front proxy could read differently.
- **Add JVM built-in config options now.** That reverses ADR 2026-11-18. Apps that need tuning can add uni-netty, which has every option.

## Preferences learned

- Context: response compression for the built-in server. Chose: a follow-up PR. User chose: include it in this PR. Principle: "port Netty's techniques as much as possible" covers response-side HTTP features too.
