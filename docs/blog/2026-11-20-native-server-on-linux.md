---
title: "Uni's Native HTTP Server, One Day Later: Level With the JVM on Linux"
date: 2026-11-20
author: Taro L. Saito
description: "A follow-up to the Scala Native server benchmarks: on Linux with 2 cores, Uni's Scala Native server now matches Uni on the JVM in throughput on a 3.4 KB response, at a fraction of the memory. What a Linux profile found, and what is left for Scala Native 0.5.13."
---

# Uni's Native HTTP Server, One Day Later: Level With the JVM on Linux

**On Linux with 2 server cores, Uni's HTTP server compiled with Scala Native now serves a 3.4 KB RPC response at 89–119% of the throughput of Uni on the JVM.** The JVM build needed 240–350 MB of memory under load, the native build about 100 MB, and the native binary answers its first request in 11–17 ms instead of 0.5–1 s.

[Yesterday's post](/blog/native-server-performance) measured the native server on an Apple laptop, where it reached about 80% of the JVM's throughput on this response, limited by contention on the Boehm garbage collector's allocation lock. That limit belongs to the GC that Scala Native 0.5.12 runs with. On Linux, where most servers run, a CPU profile showed something different: plain CPU work we could remove. This post covers what changed and what is left.

## Results on Linux

These come from the repository's `rpc-bench.yml` workflow on GitHub-hosted runners: the rpc-native example's `list` call returning 50 todos (3.4 KB of JSON, gzipped), 16 keep-alive connections, the server pinned to 2 cores and the load generator to the other 2. Shared runners vary from run to run, so each row compares the two servers on the same runner.

| Run | Arch | Uni on Scala Native | Uni on the JVM (built-in server) | Native / JVM |
|---|---|---|---|---|
| 1 | x86_64 | 15.7k req/s, p99 4.4 ms | 16.2k req/s, p99 2.7 ms | 97% |
| 1 | arm64 | 24.0k req/s, p99 3.8 ms | 20.2k req/s, p99 1.3 ms | 119% |
| 2 | x86_64 | 39.0k req/s, p99 1.9 ms | 44.0k req/s, p99 0.6 ms | 89% |
| 2 | arm64 | 23.5k req/s, p99 3.8 ms | 21.4k req/s, p99 1.2 ms | 110% |

On the 1-todo response (about 70 bytes), an earlier run on the same setup gave the native server 51.1k req/s against the JVM's 48.9k on x86_64, and 92.2k against 62.5k on arm64.

The JVM still has the shorter tail on 2 cores: its p99 is 0.6–2.7 ms, the native server's 1.9–4.4 ms. More on that below.

## What a Linux profile found

`perf` on the runners, at 50 todos, put Boehm at only 6–10% of the native server's CPU. The time went elsewhere:

- **7% in resetting the gzip compressor.** Scala Native's `Deflater` always asks zlib for its largest hash table, 128 KB, and `deflateReset` clears it for every response, even one of 3.4 KB. On Scala Native, Uni now calls zlib directly with a window and hash table sized to the body ([#1014](https://github.com/wvlet/uni/pull/1014)). Compressing the 3.4 KB response takes 6.3 µs instead of 12.8 µs; the JVM's `Deflater` takes 10.5 µs.
- **About 16% in per-character string work.** On Scala Native, `String.charAt` is a function call per character, and case-insensitive comparisons search Unicode tables; the JVM's JIT inlines both. Strings are now packed with one bulk copy, JSON strings that need no escaping are written with one copy, and header names are compared with an ASCII fast path ([#1011](https://github.com/wvlet/uni/pull/1011)).
- **A copy of each string literal on every call.** When Scala Native's optimizer inlines a method that reads a literal's characters, it materializes a new char array each time. The response head now writes precomputed bytes ([#1001](https://github.com/wvlet/uni/pull/1001)), and hot comparisons use fields instead of literals.

Smaller allocation cuts went into the same paths: shared RPC response headers ([#1003](https://github.com/wvlet/uni/pull/1003)), and JSON parser buffers created only when an input needs them ([#1019](https://github.com/wvlet/uni/pull/1019)). A 50-todo request now allocates about 18.8 KB, down from about 26 KB in yesterday's post and 462 KB before that work.

## Tail latency: one worker per core

With 16 worker threads on 2 cores, the native server's p99 was 4.7–16 ms, depending on the run. In one run, turning off garbage collection cut it from 15 ms to about 4 ms, but changing the heap size by 16x did not move it, so the pauses themselves were not the cause. Sixteen runnable threads on two cores were: when the thread holding the allocator's lock is descheduled, the others wait behind it. The JVM avoids this here by running its virtual threads on two carrier threads.

| Workers on 2 cores | x86_64 p99 | arm64 p99 | Throughput |
|---|---|---|---|
| 16 | 4.7–4.9 ms | 12.3 ms | about the same in every row |
| 8 | 3.3 ms | 9.1 ms | |
| 4 | 1.9 ms | 3.8 ms | |
| 2 | 0.66 ms | 0.73–0.77 ms | |

`NativeServer` now sizes its worker pool to the cores, from 4 to 16 ([#1016](https://github.com/wvlet/uni/pull/1016)). The floor of 4 leaves room for handlers that block and for Server-Sent Events streams, which hold a worker while open. A service on a small machine whose handlers never block can set `withWorkerThreads` to the core count for the shortest tail. On Linux, the core count follows the process's CPU affinity, so `taskset` and cpusets are respected. Windows keeps 16 workers, because each open connection holds one there.

## On the laptop

On the Apple M5 Pro from yesterday's post, CPU per request at 50 todos dropped 12–15% (to 81–83 µs at 16 connections). Throughput reached about 90% of the JVM's at 64 connections (96k against 107k req/s). The rest of that gap is Boehm's allocation lock: with the Homebrew build of Boehm, a contended wait blocks in the kernel. About 60% of the remaining allocations come from the example's own `list` handler (`todos.values.toSeq.sortBy(_.id)`), ordinary Scala that the JVM's thread-local allocation handles cheaply.

## What's next

Scala Native 0.5.13 makes its Immix collector safe for multithreaded servers ([scala-native#5159](https://github.com/scala-native/scala-native/pull/5159)), and Immix allocates without a global lock. A nightly build of 0.5.13 served the rpc-native benchmark on macOS without failures and with almost no lock waits. When 0.5.13 is released, we plan to measure Immix on Linux and macOS with these changes in place and switch the recommendation if it holds up.

## How we measured

- **Linux:** GitHub-hosted `ubuntu-24.04` (x86_64) and `ubuntu-24.04-arm` (arm64) runners, 4 vCPUs. `rpc-bench.yml` with `items=50`, which pins the server to cores 0–1 and oha 1.16 to cores 2–3, with a warm-up before each measured run. Scala Native 0.5.12, release-full mode, full LTO, Boehm GC 8.2.6 from Ubuntu, `GC_INITIAL_HEAP_SIZE=64m` as `bench.py` sets it; JDK 25 for the JVM build.
- **Profiles:** `perf record -g` on the same runners; the code is in the [bench directory](https://github.com/wvlet/uni/tree/main/examples/rpc-native/bench).
- **macOS:** as in [yesterday's post](/blog/native-server-performance). The machine also ran other work during these runs, so treat differences under about 10% as noise.

To reproduce the Linux table, run the `RPC benchmark` workflow with `items` set to 50, or locally: `examples/rpc-native/bench/build.sh`, then `python3 examples/rpc-native/bench/bench.py --items 50 --server-cpus 0-1 --client-cpus 2-3`.
