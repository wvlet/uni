# Native server: start Boehm with a 16 MB heap

## Goals

- Close most of the remaining gap on larger responses. On `main`, a 3.4 KB RPC response served 47.9k req/s against the JVM's 93.2k. Boehm collects constantly because a server keeps little live data, so its heap stays at 2–3 MB.

## Design

Lane: Standard. It changes the memory a Native server uses, a decision for the user (asked on the question card; this PR waits for the answer before merging).

- When a Native HTTP server starts, uni grows Boehm's heap to 16 MB with `GC_expand_hp`, looked up with `dlsym` in `uni_gc_shim.c`, like the marker default in #983.
- It does nothing when `GC_INITIAL_HEAP_SIZE` is set, and nothing with other GCs. CLI apps that never start a server are unaffected.
- The size is one constant (`NativeHttpServer.ServerHeapBytes`).
- Test: the rpc-native example (a Boehm build) checks that the heap is at least 16 MB once a server starts.

## Alternatives and Why Not?

- **An 8 MB start.** It gives +39% instead of +45–50%, for about half the RSS increase.
- **Docs only.** It leaves the default 2x behind the JVM on larger responses.
