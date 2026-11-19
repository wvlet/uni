# HTTP Garden tests for Uni's HTTP servers

## Goals

- Find request-parsing differences between Uni's servers and well-tested reference servers with an outside tool, not by reading code.
- Cover the three Uni servers that parse raw HTTP/1.1: the built-in server on the JVM, the built-in server on Native, and uni-netty.
- Catch regressions: a parser change that starts accepting a smuggling payload the reference servers reject should fail a job.
- Keep the normal PR CI as fast as it is today.

## Background

- HTTP Garden (narfindustries/http-garden) is a differential tester for HTTP parsers. It runs many servers side by side, sends each the same raw bytes, and compares how each one understood the request.
- A Garden "origin" is a server that answers every request with a 200 and a JSON echo of what it parsed, with the keys `method`, `uri`, `version`, `headers`, and `body`, each base64-encoded. A server that rejects the request answers with its error status instead.
- Garden compares the echoes and ignores harmless differences, using a per-server list of known quirks (for example, "allows a missing Host header"). It reports the rest by category: one server accepts while another rejects, the parsed requests differ, one stream is split into a different number of requests, or both reject with different statuses. The first three are the patterns behind request smuggling.
- Garden builds its reference servers from source in Docker (about 35 origins, among them hyper, Netty, and Apache httpd) and only supports Linux. At the pinned commit, its Go net/http, Node.js, and h2o targets only speak TLS. This Mac has no Docker, so the job can only be run and checked on GitHub's Ubuntu runners.
- Garden can also reach servers that run outside Docker, listed with their address in its `external-services.yml` (empty at the pin). Uni's servers can therefore run on the runner as normal processes, with no Docker image of their own.
- Garden's tools find their config files relative to the running script, and connect to Docker when imported, so they must run from inside the clone after the reference servers are up.
- Garden has no ready-made batch or fuzz command. It ships a REPL plus Python modules for sending requests (`fanout`), comparing them (`categorize_discrepancy`), and mutating them (`mutations.mutate`).
- Garden is GPL-3.0 and Uni is Apache-2.0. The job clones Garden at a pinned commit when it runs; Uni stores none of Garden's code. The driver script stays in the test tooling and is never part of a published artifact.
- Uni layers some behavior on top of parsing: request decompression, Host checks, and response compression. Native and uni-netty can turn request decompression off. The built-in JVM server has no options and always decodes gzip/deflate bodies; adding an option would change its API, so this PR doesn't.
- When uni-netty is on the classpath, it replaces the built-in JVM server. The built-in JVM server and uni-netty therefore need two separate classpaths.
- Uni allows a missing Host header and answers 501 to unknown methods, while most references answer 400. Garden can only treat these as harmless if Uni's entries declare them as quirks.

## Design

Lane: Standard. It adds a new CI workflow, a new example, and test tooling.

User flow (a maintainer checking parser changes):

- **Start:** run the "HTTP Garden" workflow by hand (workflow_dispatch) on any branch. It also runs once a week on main, and on pull requests that change the Garden tooling itself.
- **The run:** it builds the reference servers, starts the three Uni echo servers, sends a fixed corpus of request-smuggling payloads plus a fixed number of seeded random mutations, and compares each Uni server against each reference server.
- **The result:** a summary table in the job: each payload where a Uni server disagrees with the references, which servers, and what each one did (accepted with which body, rejected with which status, no response). The raw report is uploaded as an artifact.
- **When it fails:** the job fails only on differences that are not in the committed list of known, accepted differences. To accept a difference, add it to that file with a one-line reason. To fix one, open a separate PR.
- **Reproducing locally:** on a Linux machine with Docker, a script runs the same steps.

Decisions:

- **Echo app:** `examples/http-garden`, a small app built with Uni's own server API, with a README and excluded from publishing. It is built three ways from one handler: JVM without uni-netty (the built-in server), JVM with uni-netty, and a Native binary. A difference therefore points at the parser, not the app. Only the Garden workflow builds and runs it, the second linked Native example after `rpc-native`. An ADR records this.
- **Reference servers:** hyper, upstream Netty, and Apache httpd. All three are strict, widely deployed, and serve plain HTTP, so no TLS is involved. Comparing uni-netty with upstream Netty also shows whether uni-netty's pipeline changes what Netty itself accepts.
- **Driver:** a short Python script of ours. At run time, it adds the three Uni servers (address, port, quirks) to the clone's `external-services.yml` and `quirks.yml`, then runs from the clone's `tools/` folder.
- **Corpus:** the request inputs from the Lean-generated `HttpReaderSpecCases.scala` (the Lean CI job already keeps it in sync with the model), plus our own file of known smuggling patterns (duplicate Content-Length, Content-Length with Transfer-Encoding, obs-fold, bare LF, chunk-size tricks, Content-Encoding). Random mutations use a fixed seed, so every run is repeatable.
- **What fails the job:** accept-versus-reject, different parsed requests, and different request splits. When both sides reject with different statuses (Uni's 501, 505, or 431 against a 400), the difference is reported but doesn't fail the job.
- **JVM decompression:** payloads with Content-Encoding are expected to differ on the built-in JVM server, and the driver treats them as known differences.
- **Pinned versions:** the Garden commit and the reference servers' commits are pinned. Updating them is a deliberate PR.
- **uni-netty:** included, but report-only. uni-netty stays unchanged, so its differences go in the summary and never fail the job.
- **Findings:** this PR adds the job and the list of known differences. If the first run finds a real bug, I will report it to you and not fix it in this PR, because fixing it changes server behavior.
- **Validation:** there is no Docker on this Mac, so the job is developed and verified by running it on GitHub's runners from this branch.

## Alternatives and Why Not?

- **Run on every PR as a required check:** building the reference servers from source takes tens of minutes, and new findings need someone to look at them before they can block a merge.
- **Garden's REPL only, no CI:** finds problems once, but doesn't catch regressions.
- **h1spec or other conformance suites:** they check against fixed expectations rather than comparing real servers, so they miss differences between servers that each look reasonable alone. They are smaller, and could be added later.
- **Docker images for Uni's servers:** this would match how Garden runs its own targets, but it adds an image build per platform, and running them as external services does the same job.
