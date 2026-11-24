# Faster Parquet dictionary indices and levels

## Goals

- Cut the CPU of dictionary-encoded columns. After #1173, a profile of the TPC-DS SF1 fact-table scan puts index decoding first: `BitPacking.unpackInts` (16% self), the RLE decoder's `read`/`nextRun` (27% inclusive), and `appendFromDictionary` with its per-value `checkIndex` (9%).
- Keep every result identical.

## Background

- DuckDB writes the TPC-DS fact tables with PLAIN_DICTIONARY, and the files have no page index. So index decode is the hot path, and page-index pruning wouldn't help these scans.

## Design

- Lane: Express. Internal performance change, no API change.
- Bit-packed runs stay in the page bytes and are unpacked as they are read, straight into the caller's array. Before, a run went into a `packed` array the size of the run, and was then copied out.
- `unpackInts` reads each value with one 64-bit load (`Bytes.wordLE`), as long as its 8 bytes are in range. The last few values go bit by bit, reading missing bytes as 0 as before.
- `RleBitPackedDecoder.readDictionary` appends values to the buffer directly:
  - An RLE run becomes one checked lookup and an `Arrays.fill` (`appendRepeated`).
  - Bit-packed indices are unpacked a block at a time. Each block's indices are range-checked in one branch-free pass, so the lookup loop has no per-value check.
- 1-bit levels expand 8 at a time from whole bytes, with `Integer.bitCount` for the count. Wider levels unpack a block at a time into a scratch array.

## Alternatives and Why Not?

- Unpack routines specialized per bit width (as parquet-java generates): faster still, but a lot of code. The 64-bit load per value already removes the inner byte loop.
