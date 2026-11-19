# uni-netty: reject request lines with non-SP separators

## Goals

- uni-netty answers 400 and closes the connection when a request line separates its words with anything other than SP octets (`GET\r /1 HTTP/1.1`, `GET\t/ HTTP/1.1`), or starts with whitespace. The built-in JVM/Native reader, hyper, and Apache httpd already reject these.
- Runs of SP (`GET  / HTTP/1.1`) get 400 on every server, uni-netty and the built-in reader alike (user's choice; the built-in reader and the Lean model accepted them before).
- Responses to earlier pipelined requests on the same connection are still written before the 400.
- The weekly HTTP Garden run on main passes again.

## Background

- Garden run [44486194520](https://github.com/wvlet/uni/actions/runs/44486194520) on #972's final commit has one new uni_netty finding: `GET\r /1 HTTP/1.1 ...`. uni-netty serves `GET /1`, then answers 400 to the next request. A proxy that reads the bytes differently would see different requests, so this is a request-smuggling risk.
- #972 didn't cause it. Its new Lean seed cases changed the random mutations, and one of them hit behaviour that was already there.
- Cause: Netty 4.2.21 splits the request line with `findSPLenient`, which treats SP, HTAB, VT, FF, and bare CR as separators and collapses runs of them. `HttpDecoderConfig` has no option to turn this off.
- The parsed `HttpRequest` keeps only the three words, so a check placed after the codec can't see which separators were used.
- Netty's `HttpRequestDecoder` has protected hooks, `splitFirst/Second/ThirdWordInitialLine(bytes, start, length)`. Each receives the raw line bytes and its word's offsets, so a subclass can see the bytes between the words. An exception thrown there goes through Netty's usual invalid-message path: the decoder emits a failed request and discards the rest of the input. `DecodeFailureHandler` already turns that into a 400 and a close once earlier pipelined responses are written.
- `HttpServerCodec` is `final`, and its decoder is a private inner class, so the decoder can't be replaced inside it.
- `setUseRfc9112TransferEncoding` is already `true` by default in 4.2.21: a request with both `Transfer-Encoding` and `Content-Length` is rejected. `setStrictLineParsing` is also on by default, so bare-LF line endings are rejected too.

## Design

Lane: Standard — a security boundary (request smuggling), and the codec layout of every uni-netty connection changes.

- Replace `HttpServerCodec` with a separate decoder and encoder, the two-handler layout that Netty also documents:
  - **Decoder:** a `HttpRequestDecoder` subclass, built with today's limits (initial line, header size, and chunk size). Its word hooks check that only SP octets sit between method and target, and between target and version. It also rejects whitespace before the method, which Netty skips today. Otherwise it throws, and Netty marks the request failed. Every other parsing rule stays Netty's.
  - The leading-whitespace check relies on a Netty internal: the line is copied to offset 0 of an unpooled scratch buffer. A test pins it, so a Netty upgrade that changes it fails the build.
  - A line with no version skips the third hook, but Netty already rejects its empty version.
  - **Encoder:** a `HttpResponseEncoder` subclass.
  - **Shared per-connection state** keeps what `HttpServerCodec` adds: the decoder queues each request's method and the encoder takes them off, so a response to `HEAD` gets no body and a `2xx` to `CONNECT` loses `Transfer-Encoding`. The cap of 128 queued pipelined requests stays. A rejected request stays queued, as today, and its 400 takes it off.
- The failed request reaches `DecodeFailureHandler` like any other decode failure, so pipelining, bodies, and the pending-response count need no changes there.
- Netty's WebSocket handshake looks up `HttpRequestDecoder` and `HttpResponseEncoder` in the pipeline. It finds both with the two-handler layout, so upgrades keep working.
- Set `HttpDecoderConfig.setUseRfc9112TransferEncoding(true)` and `setStrictLineParsing(true)` explicitly. A JVM system property can no longer turn them off.
- ADR: update `adr/2026-11-18-socket-server-netty-parity.md`, which names `HttpServerCodec`, and add the request-line separator rule to its strict-parsing list.
- Garden: no new `known-differences.yml` entry. The finding goes away.
- Tests: raw-socket tests for HTAB, VT, FF, bare CR, and double SP in each separator position, including behind a pipelined valid request with a body; and regression tests that `HEAD`, pipelining, and a WebSocket upgrade still work.

### Two status-only differences in the built-in reader (included, user's choice)

Neither one fails the Garden run, and each fix is a few lines in `SocketHttpProtocol` plus the matching rule in the Lean model (`HttpReader.lean`) and its generated cases.

- **Chunk size line ending in a bare LF** (`7fffff\nff`): the reader checks the size against the limit before it checks the line ending, so it answers 413. Swapping the two checks gives 400, as Apache does.
- **`chunked` applied twice** (`Transfer-Encoding: chunked` sent twice): today 501, as for any list of codings. RFC 9112 forbids applying `chunked` more than once, so 400 when `chunked` appears before the last coding. `identity, chunked` stays 501.

## Alternatives and Why Not?

- **A byte check in front of the codec.** It would have to find where each pipelined request starts, which means parsing Content-Length and chunked bodies a second time, in a second parser that could disagree with Netty's.
- **A check after the codec.** The parsed request has lost the separators.
- **Wait for an upstream Netty option.** None exists, and main's Garden run fails until this is fixed. We can still file the issue upstream.
- **Copy `HttpServerCodec` whole as a combined handler.** The WebSocket handshake doesn't recognize a combined handler under another class name.
