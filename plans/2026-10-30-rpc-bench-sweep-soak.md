# rpc-native benchmark: concurrency sweep and soak

## Goals

- Finish the launch plan's measurement list: a concurrent-connections sweep with p50/p99 per level.
- Answer whether Native RSS levels off under sustained load (8 → 38 MB locally, 17 → 56 MB on Linux x86_64 after one load run), so the post's memory claim holds or gets corrected.

## Background

- `NativeServer` is thread-per-connection with `workerThreads = 16` and a 30 s keep-alive idle timeout, so above 16 keep-alive connections the extra ones wait in the accept queue instead of failing.
- oha reports requests still in flight at the deadline as "aborted due to deadline"; bench.py drops them from failures, which would hide starved connections above 16.
- A parallel fix (fix-nativeserver-linux-load) targets Native stalls on arm64 and dropped keep-alive connections on x86_64; until it lands, Linux runs show Native failures.

## Design

- Lane: Standard (new benchmark modes and workflow inputs); direction approved in the request, so the design gate is transcribed.
- `--sweep 1,16,64,256`: one server per target, warm-up once, then each level for `--duration`; table of req/s, p50, p99, failed requests, requests in flight at the deadline, and RSS after each level, per target.
- The "in flight at deadline" column makes queueing above `workerThreads` visible instead of hiding it as non-failures.
- `--soak 10m --sample 15s`: one server per target held under `-c` load as back-to-back oha runs of one sample interval each, RSS read between runs; time-series table plus a verdict line per target (peak RSS, growth over the last half, "levels off" or "still growing").
- Back-to-back short runs instead of one long oha run: oha keeps every latency in memory, which a 10-minute run at tens of thousands of req/s would make large; short runs also give req/s and failures per sample.
- Sweep and soak replace the standard table when given (they can be combined); the default run is unchanged.
- rpc-bench.yml gets a `mode` choice (standard, sweep, soak) plus `sweep`, `soak`, and `sample` inputs; only standard builds container images; results go to the job summary and the JSON artifact.

## Alternatives and Why Not?

- Fresh server per sweep level: cleaner per-level RSS, but loses the cumulative growth signal and repeats JVM warm-up.
- Run the sweep on every PR: triples the PR run time; dispatch covers pre-launch measurement.
