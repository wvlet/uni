#!/usr/bin/env python3
"""Plant bugs in the built-in HTTP reader and check that the Lean-generated cases catch each one.

Each mutant below is a bug a lenient HTTP parser has shipped, written as exact text replacements
in SocketHttpProtocol.scala. For each one, this script applies it, runs HttpReaderSpecTest (the
cases generated from spec/lean/UniSpec/HttpReader.lean), and restores the file. A mutant is

- killed: a case failed, as it should;
- survived: every case passed, so a case is missing (add an input to GenHttpReaderCases.lean);
- invalid: the mutant did not compile.

Run it from the repository root, on demand or after changing the model:

    python3 spec/lean/mutants/mutate_http_reader.py [--fuzz] [name ...]

With names, only those mutants run. With --fuzz, each mutant runs HttpReaderFuzzTest instead, with a
fixed UNI_FUZZ_SEED (override it in the environment), to measure what fuzzing finds on its own: the
smuggling rule of HttpSmuggle.lean checked on mutated inputs, the same outcomes for every read
size, and the outcomes of the Lean model itself. For that it first builds the model's fuzz oracle
(`lake build http-reader-oracle`, so it needs Lean) and passes it in UNI_LEAN_HTTP_ORACLE. It
refuses to start when the reader has uncommitted changes,
and always restores the file, so the working tree is left as it was.
"""

import os
import subprocess
import sys
from pathlib import Path

TARGET = Path("uni/.jvm-native/src/main/scala/wvlet/uni/http/SocketHttpProtocol.scala")
SPEC_TEST = ["./sbt", "uniJVM/testOnly wvlet.uni.http.HttpReaderSpecTest"]
FUZZ_TEST = ["./sbt", "uniJVM/testOnly wvlet.uni.http.HttpReaderFuzzTest"]

# (name, what the bug does, [(old, new), ...]); each `old` must occur exactly once
MUTANTS = [
    (
        "bare-lf-in-headers",
        "a header line may end at a bare LF",
        [("if !cr then\n            scanError", "if false then\n            scanError")],
    ),
    (
        "bare-cr-before-request-line",
        "a CR without its LF is skipped before the request line, like an empty line",
        [
            (
                "else if buf(start) == '\\r' && buffered > 1 && buf(start + 1) == '\\n' then\n        start += 2",
                "else if buf(start) == '\\r' then\n        start += 1",
            )
        ],
    ),
    (
        "ws-before-colon",
        "a header name may hold whitespace, so `Content-Length :` names another field",
        [("if !isTokenChar(at(i)) then", "if !isTokenChar(at(i)) && !isBlank(at(i)) then")],
    ),
    (
        "obs-fold",
        "an obs-fold line is read as a field line",
        [("if first == ' ' || first == '\\t' then", "if false then")],
    ),
    (
        "trailing-ws-kept",
        "trailing whitespace stays in field values",
        [("while ve > vs && isBlank(at(ve - 1)) do", "while false do")],
    ),
    (
        "last-content-length-wins",
        "of several Content-Length values, the last one frames the body",
        [
            ("contentLengths.sizeIs > 1 ||", "false ||"),
            ("val v = contentLengths.head", "val v = contentLengths.last"),
        ],
    ),
    (
        "length-wins-over-chunked",
        "with both framing headers, Content-Length frames the body",
        [
            (
                "val chunked           = transferEncodings.nonEmpty",
                "val chunked           = transferEncodings.nonEmpty && contentLengths.isEmpty",
            )
        ],
    ),
    (
        "keep-alive-after-both-framings",
        "the connection stays open after a request framed both ways",
        [("!(chunked && contentLengths.nonEmpty)", "true")],
    ),
    (
        "chunked-twice",
        "chunked may be applied twice",
        [('codings.init.contains("chunked")', "false")],
    ),
    (
        "chunked-on-http10",
        "Transfer-Encoding is accepted on HTTP/1.0",
        [
            (
                'if http10 then\n        return bad("Transfer-Encoding',
                'if false then\n        return bad("Transfer-Encoding',
            )
        ],
    ),
    (
        "two-hosts",
        "two Host headers are accepted",
        [("if headers.getAll(HttpHeader.Host).sizeIs > 1 then", "if headers.getAll(HttpHeader.Host).sizeIs > 2 then")],
    ),
    (
        "connection-close-ignored",
        "Connection: close keeps the connection open",
        [('!connectionTokens.contains("close") &&', "true &&")],
    ),
    (
        "control-in-target",
        "a tab or other control byte may appear in the request target",
        [("if isControl(at(k)) then", "if at(k) == 0 then")],
    ),
    (
        "chunk-data-without-crlf",
        "chunk data may end at a CR without its LF",
        [
            (
                "if at(p + len) != '\\r' || at(p + len + 1) != '\\n' then",
                "if at(p + len) != '\\r' then",
            )
        ],
    ),
    (
        "body-limit-off-by-one",
        "a Content-Length equal to the limit is too large",
        [("if declaredLength > maxBody then", "if declaredLength >= maxBody then")],
    ),
    (
        "chunk-limit-off-by-one",
        "a chunk that reaches the body limit exactly is too large",
        [("if size > limits.maxContentLength - total then", "if size >= limits.maxContentLength - total then")],
    ),
    (
        "request-line-limit-off-by-one",
        "a request line one byte over the limit is accepted",
        [("else if i >= limits.maxInitialLineLength then", "else if i > limits.maxInitialLineLength then")],
    ),
    (
        "header-limit-off-by-one",
        "a header block one byte over the limit is accepted",
        [("else if i - from >= maxSize then", "else if i - from > maxSize then")],
    ),
]


ORACLE = Path("spec/lean/.lake/build/bin/http-reader-oracle")


def build_oracle() -> bool:
    """Build the Lean model's fuzz oracle, which HttpReaderFuzzTest compares the reader with."""
    p = subprocess.run(["lake", "build", "http-reader-oracle"], cwd="spec/lean", capture_output=True, text=True)
    if p.returncode != 0:
        print(f"lake build http-reader-oracle failed:\n{p.stdout}{p.stderr}", file=sys.stderr)
    return p.returncode == 0


def run_test(test: list[str]) -> tuple[str, str]:
    """Run the test: ("pass" | "fail" | "invalid", the last lines of its output)."""
    env = {
        **os.environ,
        "UNI_FUZZ_SEED": os.environ.get("UNI_FUZZ_SEED", "1"),
        "UNI_LEAN_HTTP_ORACLE": str(ORACLE.resolve()),
    }
    p = subprocess.run(test, capture_output=True, text=True, errors="replace", env=env)
    out = p.stdout + p.stderr
    ran = any("Total:" in line for line in out.splitlines())
    tail = "\n".join([line for line in out.splitlines() if "Total:" in line or "[error]" in line][:5])
    if p.returncode == 0 and ran:
        return "pass", tail
    return ("fail" if ran else "invalid"), tail


def main() -> int:
    if not TARGET.exists():
        print(f"Run from the repository root: {TARGET} not found", file=sys.stderr)
        return 2
    dirty = subprocess.run(["git", "status", "--porcelain", "--", str(TARGET)], capture_output=True, text=True)
    if dirty.stdout.strip():
        print(f"{TARGET} has uncommitted changes; commit or revert them first", file=sys.stderr)
        return 2

    args = sys.argv[1:]
    fuzz = "--fuzz" in args
    names = [a for a in args if a != "--fuzz"]
    test = FUZZ_TEST if fuzz else SPEC_TEST
    if fuzz and not build_oracle():
        return 2
    test_name = test[1].split()[-1].rsplit(".", 1)[-1]
    selected = [m for m in MUTANTS if not names or m[0] in names]
    original = TARGET.read_text()
    for name, _, edits in selected:
        for old, _ in edits:
            if original.count(old) != 1:
                print(f"{name}: expected one occurrence of {old!r}, found {original.count(old)}", file=sys.stderr)
                return 2

    status, tail = run_test(test)
    if status != "pass":
        print(f"The unmutated reader does not pass {test_name} ({status}):\n{tail}", file=sys.stderr)
        return 2

    results = []
    try:
        for name, what, edits in selected:
            mutated = original
            for old, new in edits:
                mutated = mutated.replace(old, new)
            TARGET.write_text(mutated)
            status, _ = run_test(test)
            verdict = {"fail": "killed", "pass": "SURVIVED", "invalid": "invalid"}[status]
            results.append((name, verdict))
            print(f"{verdict:9} {name}: {what}", flush=True)
    finally:
        TARGET.write_text(original)

    survived = [n for n, v in results if v != "killed"]
    print(f"\n{len(results) - len(survived)} of {len(results)} mutants killed")
    if survived:
        print("Not killed: " + ", ".join(survived))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
