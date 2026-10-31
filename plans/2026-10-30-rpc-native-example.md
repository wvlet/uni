# Typed RPC without the JVM: example and benchmark

## Goals

- Back the launch post with a copyable example: one Scala 3 trait served as typed RPC by Scala Native (`NativeServer`) and by Netty on the JVM.
- Make the post's numbers reproducible by anyone: scripts in the repo, plus a CI workflow that measures on Linux x86_64 and arm64.

## Background

- A spike on an Apple Silicon Mac showed Native at 8 ms to first response and 11 MB RSS vs. ~300 ms and ~100 MB for the JVM; JVM throughput was somewhat higher.
- #831 and #832 removed stack traces from RPC errors and libcrypto from Native, so a server-only binary links only libc.
- The repo's `nativeBuildSettings` always adds `-lcurl -lz`; the example must filter them out to show what a user's build gets.

## Design

- Lane: Standard (new example, new CI workflow); the direction was given in full by the request, so it proceeds without a design gate. No merge without the user's approval.
- `examples/rpc-native` is a crossProject (JVM + Native): shared `TodoApi` trait, impl, and a typed `TodoApiClient`; each platform's `Main` serves it through `RPCDispatcher` on `NettyServer` or `NativeServer`.
- A JVM UniTest suite starts the server on a free port and calls it through the typed client. The Native side gets no test binary; CI instead links it, runs it, calls it, and checks it links no libcurl.
- Benchmark lives in `examples/rpc-native/bench/`: one Python script (stdlib only) that measures launch → first RPC response (median of N), RSS after first call and after load, and req/s with p50/p99 from `oha` (falling back to `wrk`). It runs the JVM with defaults and with a small heap, and prints a Markdown table with OS, CPU, JDK, Native mode, and load settings.
- A `workflow_dispatch` workflow runs the script on ubuntu x86_64 and arm64, builds a container image per build (Native on a minimal base; JVM on a JRE image), records image size and container cold start, and writes it all to the job summary.
- The Native mode is chosen by measuring `releaseFast`, `releaseFull`, and LTO locally, then fixed in the build and named in the README.

## Alternatives and Why Not?

- JMH or a Scala harness for the benchmark: measures in-process, not launch-to-response of a separate binary.
- Running the benchmark on every PR: shared runners make it noisy and slow; manual dispatch fits a pre-launch measurement.
