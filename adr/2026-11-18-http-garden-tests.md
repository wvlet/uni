# HTTP Garden differential tests for Uni's HTTP servers

## Context

The built-in socket server (JVM and Native) parses raw HTTP/1.1 itself, and its request-smuggling defenses were ported from Netty by reading code. Reading code finds the differences someone thinks to look for. HTTP Garden (narfindustries/http-garden) sends the same bytes to many servers and flags where their readings differ, which finds the ones nobody looked for.

## Decision

- `examples/http-garden` holds a Garden echo origin built with `Http.server`. One handler runs as the built-in JVM server (the classpath without the Netty jars, as in the rpc-native benchmark), as a Scala Native binary, and on uni-netty. Native and uni-netty turn request decompression off; the built-in JVM server has no option for it, so its `Content-Encoding` payloads are treated as known differences instead of adding an option for a test.
- The references are Garden's hyper, Netty, and Apache httpd origins: strict, widely deployed, and plain HTTP. Garden's Go, Node.js, and h2o origins need TLS at the pinned commit, which is out of scope.
- `.github/workflows/http-garden.yml` runs weekly on main, on `workflow_dispatch`, and on pull requests that change the Garden tooling or uni-netty. It is not a required check: building the references from source takes tens of minutes, and a new finding needs someone to look at it before it can block a merge.
- The Uni servers run on the runner as Garden external services, not as Docker images. `uni_garden.py` writes their address and quirks into the clone's `external-services.yml` and `quirks.yml`, and runs from the clone's `tools/` folder because Garden's modules find their config relative to the running script and connect to Docker when imported.
- A finding is a payload where a Uni server agrees with no reference (accept versus reject, a different parsed request, a different request split), or where Uni accepts a request whose bytes the RFCs forbid. A difference only in the rejection status is counted but does not fail the run. A new finding on any of the three servers fails. `uni_netty` was report-only at first: its first run showed that it ran the handler on requests Netty's decoder had failed on, fixed by rejecting a failed decode right after the codec (see `2026-10-14-http-server-secure-defaults.md`).
- The seeds are the Lean-generated reader cases (`HttpReaderSpecCases.scala`) plus `corpus.txt`; Garden's mutators add a fixed number of mutations from a fixed seed.
- Garden is GPL-3.0, so it is cloned at a pinned commit when the job runs and none of its code is committed.

## Consequences

- Updating Garden or its reference servers is a deliberate PR that bumps `GARDEN_COMMIT` in `run.sh`; the reference servers' commits come from the pinned `docker-compose.yml`.
- `known-differences.yml` matches exact payload bytes. A change to the seed, the mutation count, Garden's mutators, or the corpus can change which mutated payloads are sent, so its mutated entries may need updating then.
- The Lean cases can only be compared on what Garden sees on the wire. Size limits, timeouts, and `100 Continue` stay covered by `HttpReaderSpecTest` and the server tests.
- The job needs Linux with Docker; it cannot run on macOS.
