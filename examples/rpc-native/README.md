# Typed RPC without the JVM

One Scala 3 trait, `TodoApi`, served as a typed RPC service two ways: by `NativeServer` as a
Scala Native binary (no JVM), and by Netty on the JVM. The RPC code is shared; only the server
class in each `Main` differs. A typed client calls either one through the same trait.

## What it shows

- **One trait, two runtimes**: [`TodoApi.scala`](src/main/scala/example/rpc/TodoApi.scala) holds
  the trait, its implementation, and `TodoRpc.handler`, an `RPCDispatcher` over
  `RPCRouter.of[TodoApi]`. Each method becomes `POST /example.rpc.TodoApi/<method>`.
- **Native server**: [`.native/.../Main.scala`](.native/src/main/scala/example/rpc/Main.scala)
  passes the handler to `NativeServer`, built on POSIX sockets. The binary links only OS
  libraries: no JVM, no libcurl, no OpenSSL (see [Native build mode](#native-build-mode)).
- **JVM server**: [`.jvm/.../Main.scala`](.jvm/src/main/scala/example/rpc/Main.scala) passes the
  same handler to `NettyServer` from `uni-netty`.
- **Typed client**: `TodoApiClient extends TodoApi` delegates to `RPCClient.callSync`, so calls
  are compile-checked. sbt-uni's `uniHttpClients` code generator produces this class for you.
- **UniTest**: [`TodoApiTest`](.jvm/src/test/scala/example/rpc/TodoApiTest.scala) starts the
  server on a free port and calls it through the typed client.
- **Benchmark**: [`bench/`](bench/) measures startup, memory, and throughput of both servers and
  prints a Markdown table.

## Run

```bash
# builds the binary under target/out/native0.5/.../uni-example-rpc-native
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 ./sbt exampleRpcNativeNative/nativeLink
./sbt "exampleRpcNativeJVM/run 8080"      # the same server on the JVM
./sbt exampleRpcNativeJVM/test
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
HTTP call. Add `-lcurl` only if the binary calls other servers through Uni's client. (This
repository adds `-lcurl -lz` to every Native project, so the example's build filters them out.)

Link release builds with `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0` in sbt's environment (see
[GC yieldpoints](#gc-yieldpoints)).

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

Linked libraries: without LTO, the binary links only libc (`libSystem` on macOS). With LTO it also
links the system C++ runtime (`libc++` on macOS, `libstdc++` on Linux), which ships with the OS
and with the `distroless/cc` container base.

## GC yieldpoints

In release modes, Scala Native 0.5.12 stops threads for garbage collection with trap-based
yieldpoints by default. Under concurrent load they can let the GC free or overwrite objects that
are still in use ([scala-native#5046](https://github.com/scala-native/scala-native/issues/5046),
[#5160](https://github.com/scala-native/scala-native/issues/5160)). `NativeServer` then fails
requests, sends garbled responses, or aborts. On Linux runners with two server cores, every
benchmark run failed this way: arm64 aborted within a second, and x86_64 dropped connections.

Link with conditional yieldpoints instead. Scala Native reads the switch from sbt's environment at
link time, so set it where sbt runs:

```bash
SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 ./sbt nativeLink
```

With it, the same runs served every request, and throughput on the M5 Pro stayed at ~93k req/s.
This example's build refuses to link in a release mode without it, and
[`bench/build.sh`](bench/build.sh) sets it. Debug builds already use conditional yieldpoints. Run
`clean` after changing the variable: objects compiled under the other setting are reused otherwise,
and the link fails on `scalanative_GC_yieldpoint_trap`.
