#!/usr/bin/env python3
"""Generates compressed fixtures for the codec tests under src/test/resources/codec.

The inputs come from a small linear congruential generator that CodecTestData.scala reproduces, so
the uncompressed data does not need to be committed. Needs the zstd, lz4, and brotli CLIs.
Run by hand when the fixtures need to change.
"""
import os
import subprocess
import sys

WORDS = ["parquet", "column", "row", "group", "page", "dictionary", "encoding", "scala",
         "uni", "value", "level", "null", "struct", "list", "map", "compression", "zstd",
         "snappy", "brotli", "lz4", "the", "a", "of", "and", "to", "in", "is", "for"]


def lcg(seed):
    s = seed
    while True:
        s = (s * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        yield s >> 33


def text(n, seed):
    g = lcg(seed)
    out = bytearray()
    while len(out) < n:
        out += WORDS[next(g) % len(WORDS)].encode()
        out += b"\n" if next(g) % 11 == 0 else b" "
    return bytes(out[:n])


def random_bytes(n, seed):
    g = lcg(seed)
    return bytes(next(g) & 0xFF for _ in range(n))


INPUTS = {
    "text": lambda: text(300_000, 1),
    "random": lambda: random_bytes(20_000, 2),
    "repeat": lambda: b"x" * 300_000,
    "small": lambda: b"hello",
    "empty": lambda: b"",
    "mixed": lambda: text(100_000, 3) + random_bytes(10_000, 4) + b"z" * 140_000 + text(50_000, 1),
}

ZSTD_ARGS = {
    "1": ["-1"],
    "3": ["-3"],
    "9": ["-9"],
    "19": ["-19"],
    "22": ["--ultra", "-22"],
    "long": ["-19", "--long=24"],
    "nocheck": ["-3", "--no-check"],
}


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "codec")
    os.makedirs(root, exist_ok=True)
    for f in os.listdir(root):
        os.remove(os.path.join(root, f))
    for name, gen in INPUTS.items():
        data = gen()
        for tag, args in ZSTD_ARGS.items():
            out = subprocess.run(["zstd", "-q", "-c", *args], input=data, check=True, capture_output=True).stdout
            open(os.path.join(root, f"{name}.{tag}.zst"), "wb").write(out)
        # Two frames back to back
        a = subprocess.run(["zstd", "-q", "-c", "-3"], input=data[: len(data) // 2], check=True, capture_output=True).stdout
        b = subprocess.run(["zstd", "-q", "-c", "-3"], input=data[len(data) // 2 :], check=True, capture_output=True).stdout
        open(os.path.join(root, f"{name}.frames.zst"), "wb").write(a + b)
        # LZ4 block format (as Parquet's LZ4_RAW stores it) is not exposed by the lz4 CLI, so the
        # LZ4 tests rely on the Parquet files instead
        if subprocess.run(["which", "brotli"], capture_output=True).returncode == 0:
            for q in ["1", "5", "11"]:
                out = subprocess.run(["brotli", "-c", "-q", q], input=data, check=True, capture_output=True).stdout
                open(os.path.join(root, f"{name}.q{q}.br"), "wb").write(out)
            out = subprocess.run(["brotli", "-c", "-q", "11", "--lgwin=24"], input=data, check=True, capture_output=True).stdout
            open(os.path.join(root, f"{name}.w24.br"), "wb").write(out)
        out = subprocess.run(["gzip", "-c", "-9"], input=data, check=True, capture_output=True).stdout
        open(os.path.join(root, f"{name}.gz"), "wb").write(out)
        out = subprocess.run(["gzip", "-c", "-1"], input=data, check=True, capture_output=True).stdout
        open(os.path.join(root, f"{name}.1.gz"), "wb").write(out)


if __name__ == "__main__":
    sys.exit(main())
