# Consistent rejection statuses across Uni's HTTP servers

## Goals

- A malformed request gets the same status from the built-in JVM server, the Native server, and uni-netty. Today a few payloads get 400 from one and 413, 501, or 505 from another.
- The HTTP Garden report shows each payload where only the rejection status differs, so the next mismatch is visible without rerunning anything.
- CI stops failing on a dropped sbt launcher download.

## Background

- After #968, Garden reports 0 new findings and 41 payloads where only the rejection status differs from the references (hyper, Netty, Apache httpd). The report recorded only the count. This branch's first commit records each one, and [run 44480794901](https://github.com/wvlet/uni/actions/runs/44480794901) lists them.
- Most of the 41 are expected: Uni answers 501 to a method outside its list (`BREW`, `PROPFIND`, lowercase `get`), where the references echo the request. That's the declared `method-whitelist` quirk.
- Four groups are real Uni-vs-Uni inconsistencies:
  - **A number Netty can't parse** (a Content-Length past `Long`, a chunk size past `Int`).
    - Content-Length `99999999999999999999`: the built-in servers say 413, because they cap the value while parsing. uni-netty, hyper, and httpd say 400, and Netty closes.
    - Chunk size `ffffffffffffffffffff`: the built-in servers say 413, uni-netty says 400.
    - Neither number is a usable length, so 400 fits.
  - **Unknown method plus a broken header** (`PLAY` with an overflowing Content-Length, `POS` with `Content-Length: 2, 2`): the built-in servers check the method before the framing headers and say 501. uni-netty says 400, because Netty validates the headers first. The references say 400 or close.
  - **`HTTP/2.0` in an HTTP/1 request line:** the built-in servers say 505. uni-netty serves the request as usual, or says 501 when the method is unknown, because Netty's decoder accepts any `NAME/x.y`.
- Every built-in rejection closes the connection, and so does each uni-netty one this PR adds, so no rejection lets a proxy and Uni read the bytes differently. This PR is about correct, consistent statuses, not smuggling.
- 9 of the last 25 failed CI runs stopped at "Checksum does not match": the `./sbt` script fetches the launcher's `.md5` once, with no retry.

## Design

Lane: Standard — I chose the scope, and the change alters statuses that clients see.

- **Built-in servers (JVM and Native share the reader):**
  - A Content-Length that doesn't fit in a `Long`, or a chunk size that doesn't fit in an `Int`, gets 400, as Netty's parsers decide. A valid number over the limit still gets 413.
  - The Host-count and framing-header checks run before the method check, so a request with broken headers gets 400 rather than 501. An unknown method on well-formed headers still gets 501, and the connection still closes.
- **uni-netty:** a new check right after the codec, next to the decode-failure check, looks at the version before decompression, aggregation, or the method check. Then the status doesn't depend on the body.
  - a protocol other than `HTTP` gets 400;
  - an `HTTP` version other than 1.0 or 1.1 gets 505;
  - both close the connection.
- **Lean model** (`spec/lean/UniSpec/HttpReader.lean`) gets the same check order and the overflow rule, and its generated cases cover them.
- **ADR:** `adr/2026-11-18-socket-server-netty-parity.md` records the 413/501/505 rules, so it's updated to the new order and the overflow rule.
- **Garden report:** done on this branch (`report.json` lists each status-only difference, and the summary tables them by status). Remaining: split the count into unknown-method 501s and everything else, so the table shows only the second kind. Status-only differences still don't fail the run.
- **`./sbt` launcher:** done on this branch. The download retries transient failures, and a jar that fails its checksum is deleted, so a later run can't use it unchecked.
- Tests: raw-socket tests for each changed status on the built-in server and uni-netty. The Garden run on this PR confirms the four groups are gone.

## Left as they are

- **A broken chunk body with an unknown method** (`POS` with no chunk size): the built-in servers answer 501 before reading the body, so they never see the bad chunk. Reading the body of a request we'll reject anyway isn't worth it, and the connection closes.
- **An unknown method on a well-formed request:** the built-in servers say 501 and close. uni-netty says 501 and keeps the connection, because its aggregator has already read the whole body. Both are safe.
- **`Transfer-Encoding: identity, chunked`:** the built-in servers say 501, as RFC 9112 allows for an unsupported coding. uni-netty's Netty ignores `identity`. The stricter answer is safe, so the known-differences entries stay.
- **Whitespace after a chunk size:** every Uni server rejects it. The references accept it, and the known-differences entries stay.
- **An unsupported version plus a malformed header:** the built-in servers say 505, because they check the version on the request line. uni-netty says 400, because Netty reports the header error before it hands over the request.
- **A lowercase `http/1.1`:** the built-in servers say 400. Netty upper-cases the version before parsing it, so uni-netty can't tell the two apart.

## Alternatives and Why Not?

- **Answer 400 to `HTTP/2.0`, as hyper and httpd do.** RFC 9110 defines 505 for exactly this case, and the built-in servers already send it.
- **List these in `known-differences.yml`.** They don't fail the run anyway, and the point is that Uni's own servers disagree with each other.
