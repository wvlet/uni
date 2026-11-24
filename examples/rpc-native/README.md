# Typed RPC without the JVM

One Scala 3 trait, `TodoApi`, served as a typed RPC service. The same code builds a JVM server
(Netty) and a Scala Native binary (no JVM): there is no platform-specific source file. A typed
client calls either one through the same trait.

## What it shows

- **One trait, two runtimes**: [`TodoApi.scala`](src/main/scala/example/rpc/TodoApi.scala) holds
  the trait, its implementation, and `TodoRpc.handler`, an `RPCDispatcher` over
  `RPCRouter.of[TodoApi]`. Each method becomes `POST /example.rpc.TodoApi/<method>`.
- **One server**: `TodoRpc.design` wires the server with `Design`, and
  [`Main.scala`](src/main/scala/example/rpc/Main.scala) builds it. `Http.server` picks the
  platform's server: Netty (from `uni-netty`) on the JVM, and `NativeServer`, built on POSIX
  sockets, in the Scala Native binary. The binary links only OS libraries and zlib: no JVM,
  no libcurl, no OpenSSL, no libgc (see [Garbage collector](#garbage-collector)).
- **Typed client**: `TodoApiClient extends TodoApi` delegates to `RPCClient.of[TodoApi].newSyncClient(http)`, so calls
  are compile-checked. sbt-uni's `uniHttpClients` code generator produces this class for you.
- **UniTest**: [`TodoApiTest`](src/test/scala/example/rpc/TodoApiTest.scala) starts the server
  from the same design on a free port and calls it through the typed client, on the JVM and on
  Scala Native.
- **Benchmark**: [`bench/`](bench/) measures startup, memory, and throughput of both servers and
  prints a Markdown table.

## Run

```bash
./sbt exampleRpcNativeNative/nativeLink   # builds the binary under target/out/native0.5/.../uni-example-rpc-native
./sbt "exampleRpcNativeJVM/run 8080"      # the same server on the JVM
./sbt exampleRpcNativeJVM/test exampleRpcNativeNative/test
```

Run the binary with a port, and optionally a host (`0.0.0.0` in a container; loopback by default):

```bash
target/out/native0.5/scala-3.9.0/uni-example-rpc-native/uni-example-rpc-native 8080
```

Then, from another terminal:

```bash
curl -X POST localhost:8080/example.rpc.TodoApi/add -H 'Content-Type: application/json' \
  -d '{"request":{"title":"write docs"}}'
curl -X POST localhost:8080/example.rpc.TodoApi/complete -H 'Content-Type: application/json' \
  -d '{"request":{"id":1}}'
curl -X POST localhost:8080/example.rpc.TodoApi/list -H 'Content-Type: application/json' \
  -d '{"request":{}}'
```

## Use it in your own build

```scala
// build.sbt
lazy val app = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies ++= Seq(
      "org.wvlet.uni" %% "uni"      % "<version>",
      "org.wvlet.uni" %% "uni-test" % "<version>" % Test
    ),
    testFrameworks += new TestFramework("wvlet.uni.test.Framework")
  )
  .jvmSettings(libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "<version>")
  .nativeSettings(
    nativeConfig ~= {
      _.withMode(scala.scalanative.build.Mode.releaseFull)
        .withLTO(scala.scalanative.build.LTO.full)
    }
  )
```

A server-only binary needs no linker flags: don't add `-lcurl`. Uni's HTTP *client* on Scala
Native uses libcurl, but dead-code elimination drops it when the binary never makes an outgoing
HTTP call. When it does (the example's Native test calls the server through the typed client),
Scala Native links libcurl on its own; libcurl just has to be installed. The server links zlib
(`libz`) on its own for request decompression and response compression; almost every system
provides it. (This repository adds `-lcurl` to every Native project, so the example's build
filters it out.)

For a server, use Scala Native 0.5.13 or later and cap the heap (see
[Garbage collector](#garbage-collector)).

On sbt 1.x, use `%%%` instead of `%%` for the Scala Native dependencies. `NativeServer` speaks
HTTP/1.1 without TLS; terminate TLS at a proxy or load balancer.

## Native build mode

The example builds with `Mode.releaseFull` and `LTO.full`. Each mode below was measured on an Apple
M5 Pro (macOS 27) with [`bench/`](bench/): launch → first response is the median of 7, and
throughput is the median of 4 runs of oha `-c 16` for 10 s on the same machine.

| Mode | Binary | Link time | Launch → first response | Throughput | RSS after load |
|---|---|---|---|---|---|
| `releaseFast` | 7.8 MB | 18 s | 7 ms | ~80k req/s | ~41 MB |
| `releaseFast` + `LTO.full` | 6.6 MB | 28 s | 7 ms | ~87k req/s | ~42 MB |
| `releaseFull` | 14.3 MB | 27 s | 7 ms | ~90k req/s | ~37 MB |
| **`releaseFull` + `LTO.full`** | 10.9 MB | 55 s | 6 ms | ~90k req/s | ~38 MB |

Startup is the same in every release mode. `releaseFull` with full LTO gives the best throughput
and the least memory at a smaller binary than `releaseFull` alone. Pick `releaseFast` with
`LTO.full` instead when binary size matters most. Differences under 10% are within run-to-run
noise on a laptop. Use the default `debug` mode while developing: it links fastest.

Linked libraries: without LTO, the binary links only libc (`libSystem` on macOS) and zlib. With LTO it also links the system C++ runtime (`libc++`
on macOS, `libstdc++` on Linux), which ships with the OS and with the `distroless/cc` container
base.

## Garbage collector

The example builds with Immix, Scala Native's default. Until Scala Native 0.5.13, Immix and Commix
could free or overwrite objects still in use when several threads allocated at once
([scala-native#5046](https://github.com/scala-native/scala-native/issues/5046),
[#5160](https://github.com/scala-native/scala-native/issues/5160)), so the example used Boehm
([ADR 2026-10-30](../../adr/2026-10-30-native-server-boehm-gc.md)). With 0.5.13,
[`rpc-bench`](bench/) (50 items, 16 connections, two server cores) served every request on both
collectors, and Immix was faster:

| Runner | Immix | Boehm |
|---|---|---|
| x86_64 | 24.3k req/s, p99 3.6 ms, 88 MB RSS | 15.1k req/s, p99 4.4 ms, 90 MB RSS |
| arm64 | 26.2k req/s, p99 3.1 ms, 88 MB RSS | 22.1k req/s, p99 3.9 ms, 89 MB RSS |

Shared runners are noisy, and these were separate runs: the JVM built-in server measured 24.4k and
15.4k req/s on x86_64 in them, so compare each column against it (Immix 0.99x, Boehm 0.98x on
x86_64; 1.14x and 1.08x on arm64).

Cap the heap in production. On an 18-core M5 Pro at 64 connections, Immix grew to 266 MB RSS, against
79 MB for Boehm. With `GC_MAXIMUM_HEAP_SIZE=64m` it stayed at 74 MB and served as many requests or
more. Set the cap at build time with `.withGarbageCollectorConfig(_.withMaxHeapSize(...))`
(Scala Native 0.5.13+), as this example does with 256 MB, or at run time with
`GC_MAXIMUM_HEAP_SIZE`. The container image and
`bench.py` start the server with `GC_INITIAL_HEAP_SIZE=64m`, which both collectors read.

To build with Boehm instead, set `UNI_NATIVE_GC=boehm` and install `libgc` (`libgc-dev` on
Debian/Ubuntu, `bdw-gc` on Homebrew). Uni runs Boehm with one marker thread (`GC_MARKERS` overrides
it) and starts a server with a 16 MB Boehm heap; see
[`plans/2026-11-19-native-server-throughput.md`](../../plans/2026-11-19-native-server-throughput.md)
for why. The example's test binary links Boehm to keep those settings tested.
