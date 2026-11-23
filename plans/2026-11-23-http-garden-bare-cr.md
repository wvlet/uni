# HTTP Garden: bare CR before the request line

## Goals

- Make the HTTP Garden run pass on main: its two new findings fail every run.
- Answer 400 to a bare CR before the request line on all three servers (built-in JVM, Native, uni-netty), as the parity ADR already states.
- Record the duplicate `Transfer-Encoding: chunked` 400 as a known difference.

## Background

- The socket reader skips any run of CR and LF before the request line, both in `readRequest` and in `drainLineBreaks` between requests. So `\rGET /ok ...` is served. hyper and httpd answer 400.
- Netty's decoder also skips leading control bytes, so uni-netty serves it too; `StrictHttpServerCodec`'s "nothing before the method" check never sees them.
- RFC 9112 §2.2: a server SHOULD ignore at least one empty line (CRLF) before the request line, and MAY read a bare LF as a line end. A lone CR is neither.
- Uni already rejects a bare CR everywhere else in the head.
- Two `Transfer-Encoding: chunked` lines read as `chunked, chunked`: chunked applied twice, which RFC 9112 §7.1 forbids. Uni answers 400 on purpose; the references merge the lines and accept.

## Design

- Lane: Express. A fix with a clear diagnosis, no API or settings change. Merge waits for your OK, as the launch prompt asks.
- Rule: before the request line, skip empty lines only: CRLF, or a bare LF. A CR followed by anything other than LF gets 400 ("Invalid line ending").
- The same rule holds between pipelined requests (`drainLineBreaks`) and at the start of a connection.
- A CR that is the last byte read waits for the next byte before deciding. A connection that ends or times out there closes quietly, as one with only CR/LF does today.
- uni-netty: the decoder checks the bytes before each request line itself, before Netty skips them. A bare CR fails the request through the same invalid-message path as the other request-line checks: 400, then close, after answering earlier pipelined requests.
- Lean model: `readHead` uses the same empty-line skip in place of `dropWhile isNewline`; the proofs carry over. New generated cases cover `\rGET`, `\r\r\nGET`, `\r\n\nGET`, and a bare CR between pipelined requests.
- Add a mutant that restores the old skip-any-CR loop, and confirm every mutant is still killed.
- known-differences.yml: list the duplicate-chunked payload for uni_jvm, uni_native, and uni_netty, citing RFC 9112 §7.1.

## Alternatives and Why Not?

- Reject every byte before the request line, CRLF included: RFC 9112 asks servers to skip at least one CRLF, and clients send one after a POST body.
- Keep skipping a bare CR, and list it as known: hyper and httpd reject it, and a front proxy that reads it differently would see a different request stream.
