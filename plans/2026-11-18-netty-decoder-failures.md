# Reject requests Netty failed to decode in uni-netty

## Goals

- uni-netty answers an error and closes the connection when Netty's HTTP decoder fails, instead of running the handler on a placeholder or partial request.
- Close the request-smuggling gap HTTP Garden found in PR #966: a proxy in front of uni-netty and uni-netty must not read the same bytes as different requests.

## Background

- HTTP Garden's first run (PR #966) reported about 100 uni-netty findings. Three shapes:
  - A malformed request line or header (no version, `G(T` as the method, a NUL in the target) reaches the handler anyway, as `GET /bad-request` when the request line itself is bad.
  - A chunk cut short is delivered as a partial body that swallows the start of the next request (`body='helPO'` for a 5-byte chunk).
  - Requests all three references reject are accepted, such as a bare LF after chunk data.
- Netty never throws on bad input. Its decoder marks the message with a failed `decoderResult()` and discards the rest of the connection's bytes. What it emits depends on where parsing stopped:
  - a bad request line: a `/bad-request` placeholder;
  - a bad header: the partly parsed request, with its real method and URI;
  - a bad chunk: a last-content message that carries the failure, after a request that looked fine.
- `NettyRequestHandler` never checks `decoderResult()`, so all three shapes have the same cause.
- Handlers between the codec and the request handler can lose or act on the failure:
  - the request decompressor (on by default) replaces a failed last chunk with a successful one, so the failure never reaches the handler;
  - the aggregator can answer `100 Continue` or 413 for a failed request before checking the failure, and a 413 leaves the connection open while the decoder drops every later byte.
- PR #966 kept uni-netty unchanged and made its Garden findings report-only; this PR is the follow-up that change deferred.

## Design

Lane: Standard — a security-boundary fix (request smuggling) in the HTTP server.

- A small handler right after the codec checks `decoderResult()` on every message, before decompression, aggregation, `Expect` handling, the Host check, routing, or any filter.
- On failure it drops the message and passes a decode-failure signal down the pipeline. The request handler answers it in order after any earlier pipelined responses, with `Connection: close`, and closes the connection after the write. Netty has already discarded the remaining bytes, so nothing after the bad request is read as a new one.
- Status, matching the built-in socket server where it has one:
  - request line too long → 414;
  - headers too large → 431;
  - anything else → 400.
- The response body is the plain status text. The decoder's message goes to the debug log only, as for other client errors.
- HTTP Garden: uni-netty becomes a failing server like the built-in JVM and Native servers. Any difference left after the fix is either fixed here or listed in `known-differences.yml` with a reason. Touching the Garden driver triggers the Garden workflow on this PR, which is how the fix is verified (no Docker locally).
- The Garden workflow also runs on PRs that change `uni-netty/**`, so later uni-netty changes keep the guard.
- ADRs: the Garden ADR and its index entry no longer call uni-netty report-only; the secure-defaults ADR records that a failed decode is answered and closed.
- Unit tests send raw bytes over a socket for each shape (bad request line, bad header, truncated chunk followed by a second request, the same with `Content-Encoding: gzip`, bare LF after chunk data, oversized line and headers) and check the status and that the connection closes.

## Alternatives and Why Not?

- **Check only in the request handler.** Smaller, but misses truncated chunks whenever request decompression is on (the default), and lets the aggregator send `100 Continue` or 413 for a bad request first.
- **Write the error response from the early handler.** Could reach the wire before responses to earlier pipelined requests that are still running, so the client would pair responses with the wrong requests.
- **Keep uni-netty report-only in Garden.** The fix would have no regression guard; the weekly run is the only check that exercises these byte patterns.
