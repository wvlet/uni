# Benchmark: the same RPC server on Scala Native and the JVM

Measures the [rpc-native example](../) as a native binary and on the JVM (defaults, and a small
heap with SerialGC and C1 only), so anyone can reproduce the numbers.

| Measure | How |
|---|---|
| launch → first RPC response | Spawn a fresh process, poll `POST /example.rpc.TodoApi/add` every 1 ms until it succeeds. Median and min of `--runs` launches. |
| RSS after first call | Resident set size right after that first response |
| throughput, p50/p99 latency | [oha](https://github.com/hatoo/oha) (or `wrk` if oha is missing) on `POST .../list`, keep-alive, `-c 16`, after a 5 s warm-up |
| RSS after load | Resident set size after the load run |
| container image size, cold start | Optional: `docker run` → first successful response, with `--native-image` / `--jvm-image` |

The output is a Markdown table followed by the OS, CPU, JDK, Scala Native mode, linked libraries,
and load-tool settings.

## Run locally

Needs Python 3, a JDK, the Scala Native toolchain (clang), and oha (`brew install oha`,
`cargo install oha`) or wrk.

```bash
examples/rpc-native/bench/build.sh      # builds bench/out/rpc-native and bench/out/lib/*.jar
python3 examples/rpc-native/bench/bench.py
python3 examples/rpc-native/bench/bench.py --runs 15 --duration 30s -c 64
```

On Linux, keep the load tool off the server's cores with `--server-cpus 0-1 --client-cpus 2-3`
(taskset). On a laptop, both share the same cores, so treat throughput as a rough comparison.

With Docker, also measure the images (see the Dockerfiles here: Native on `distroless/cc`, JVM
on `eclipse-temurin:25-jre`):

```bash
cd examples/rpc-native/bench
docker build -f Dockerfile.native -t rpc-native:native .
docker build -f Dockerfile.jvm -t rpc-native:jvm .
python3 bench.py --native-image rpc-native:native --jvm-image rpc-native:jvm
```

## Concurrent-connections sweep and soak

Two more modes print their own table instead of the standard one:

```bash
python3 examples/rpc-native/bench/bench.py --sweep 1,16,64,256       # 10 s per level
python3 examples/rpc-native/bench/bench.py --soak 10m --sample 15s   # per target, -c 16
```

- `--sweep` runs each connection count in turn against one server per target, after one warm-up.
  It reports req/s, p50/p99, failed requests, and RSS after each level. It also times one request
  on a fresh connection, sent 1 s into each level. NativeServer serves each keep-alive connection
  on its own worker thread (16 by default), so once 16 connections are open, a new connection
  waits until one closes. The sweep shows that as "no response", and the load tool's numbers then
  cover only the 16 connections that hold a worker.
- `--soak` holds load as back-to-back `--sample` runs and reads RSS after each one. It prints the
  RSS time series per target and a verdict: "levels off" when the second half's peak is within
  1 MB or 5% of the last first-half reading, "still growing" otherwise.

## Run on GitHub Actions

The [RPC benchmark](../../../.github/workflows/rpc-bench.yml) workflow runs this on Ubuntu
x86_64 and arm64 runners, builds both container images, and writes the tables to the job
summary. Start it from the Actions tab or with:

```bash
gh workflow run rpc-bench.yml -f runs=7 -f duration=10s -f connections=16
gh workflow run rpc-bench.yml -f mode=sweep -f sweep=1,16,64,256
gh workflow run rpc-bench.yml -f mode=soak -f soak=10m -f sample=15s
```

Shared runners make throughput noisy: neighbors share the host, and the load tool runs on the
same VM. Startup, memory, and image size are the reliable numbers there.
