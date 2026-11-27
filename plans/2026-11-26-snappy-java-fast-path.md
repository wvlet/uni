# Decode Snappy with snappy-java when the application has it

## Goal

Snappy is 27-31% of wvlet engine CPU in TPC-H SF10 q6, q17 and q21, and the pure decoder runs at about 2/3 of snappy-java's speed on the JVM. The 2026-11-24 ADR found parity out of reach with C2 on one stream. Use snappy-java's native decoder when it is available, without making it a dependency of Uni.

## Design

- `SnappyJava.java` (uni/.jvm) checks once whether snappy-java loads, and decodes through `org.xerial.snappy.Snappy.uncompress(byte[], int, int, byte[], int)`. Scala.js and Scala Native get a stub whose `available` is a compile-time `false`.
- `Snappy.decompressInto` checks the input range, the output size and the varint length before the native call, then maps snappy-java's `IOException` to `CodecException`.
- `Snappy.decode` / `decodeInto` keep the pure decoder reachable for tests and benchmarks.
- snappy-java goes from `Test` to `Provided`. The system property `wvlet.uni.snappy.useSnappyJava=false` turns the native path off.

## Still to measure

End-to-end Parquet scans and wvlet TPC-H queries with the property on and off, including several threads decoding row groups at once.
