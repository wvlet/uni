"""Compare Uni's HTTP servers with HTTP Garden reference servers.

run.sh copies this file into the tools/ folder of a pinned HTTP Garden clone and runs it from
there, because Garden's modules find their config files relative to the running script. The
reference servers must already be up (`docker compose up`), and the Uni echo servers listening on
the ports in UNI_SERVERS.

For each payload, every Uni server is compared with every reference server. A difference counts
as a finding when a Uni server agrees with none of the references, and it fails the run unless it
is in known-differences.yml.
"""

import argparse
import base64
import json
import random
import re
import sys
import time
from pathlib import Path

import yaml

GARDEN = Path(sys.path[0]).parent

# name -> port
UNI_SERVERS: dict[str, int] = {
    "uni_jvm": 18081,
    "uni_native": 18082,
    "uni_netty": 18083,
}

# How Uni's servers differ from a plain echo in ways that never matter for smuggling
UNI_QUIRKS = {
    # Uni allows a missing Host header (adr/2026-10-14-http-server-secure-defaults.md)
    "allows-missing-host-header": True,
    # Uni's Request has no HTTP version field
    "doesnt-support-version": True,
    # Methods other than these get 501
    "method-whitelist": ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"],
    # The reader collects a chunked body, drops Transfer-Encoding, and sets Content-Length to the
    # decoded size; uni-netty's aggregator does the same, and also adds Content-Length: 0. Bodies
    # are still compared byte for byte.
    # Uni's request keeps Expect after answering it; hyper, Netty, and httpd drop it
    "removed-headers": ["transfer-encoding", "expect"],
    "added-headers": ["content-length"],
}

# The built-in JVM server always decodes gzip/deflate request bodies and has no option to turn it
# off, so it echoes a different body (or 400) for any request with Content-Encoding
DECODES_CONTENT_ENCODING = {"uni_jvm"}

# Categories that point at a different reading of the byte stream
FAILING = {"TYPE_DISCREPANCY", "REQUEST_DISCREPANCY", "STREAM_DISCREPANCY"}

# Garden's default 50 ms receive timeout is too short for a JVM on a shared CI runner
TIMEOUT = 0.5


def register_uni_servers() -> None:
    """Add the Uni echo servers to the clone's config before Garden's modules read it."""
    external = {name: {"x-props": {"role": "origin", "address": "127.0.0.1", "port": port}} for name, port in UNI_SERVERS.items()}
    (GARDEN / "external-services.yml").write_text(yaml.safe_dump(external))
    quirks_path = GARDEN / "quirks.yml"
    quirks = yaml.safe_load(quirks_path.read_text()) or {}
    quirks.update({name: UNI_QUIRKS for name in UNI_SERVERS})
    quirks_path.write_text(yaml.safe_dump(quirks))


def decode(escaped: str) -> bytes:
    """Decode a JSON/Scala-style escaped string (\\r, \\n, \\t, \\", \\\\, \\uXXXX) to bytes."""
    return json.loads('"' + escaped + '"').encode("latin1")


def lean_inputs(path: Path) -> list[bytes]:
    """The request bytes of each case in the Lean-generated HttpReaderSpecCases.scala."""
    inputs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.fullmatch(r'\s*"(.*)",?', line)
        if m:
            inputs.append(decode(m[1].split("␞", 1)[0]))
    return [i for i in inputs if i]


def corpus_inputs(path: Path) -> list[bytes]:
    """One escaped payload per line; blank lines and lines starting with # are skipped."""
    lines = path.read_text(encoding="utf-8").splitlines()
    return [decode(l) for l in lines if l.strip() and not l.startswith("#")]


def unknown_method(payload: bytes) -> bool:
    """Whether the first request's method is outside Uni's list, so Uni's 501 is expected."""
    method = payload.split(b" ", 1)[0].decode("latin1")
    return method not in UNI_QUIRKS["method-whitelist"]


def show(b: bytes) -> str:
    return json.dumps(b.decode("latin1"))[1:-1]


def describe(results) -> str:
    """A short form of what one server made of a payload."""
    from http1 import HTTPRequest

    if not results:
        return "no response"
    parts = []
    for r in results:
        if isinstance(r, HTTPRequest):
            parts.append(f"{r.method.decode('latin1')} {show(r.uri)} body={show(r.body)!r}")
        else:
            parts.append(r.code.decode("latin1"))
    return "; ".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lean-cases", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--known", type=Path, required=True)
    parser.add_argument("--mutations", type=int, default=500)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--references", default="hyper,netty,apache_httpd", help="Garden origins to compare with")
    args = parser.parse_args()

    REFERENCES = args.references.split(",")
    register_uni_servers()
    # Garden connects to Docker and reads its config on import
    from diff import categorize_discrepancy
    from fanout import fanout
    from http1 import HTTPRequest
    from mutations import mutate
    from targets import SERVER_DICT

    missing = [n for n in [*UNI_SERVERS, *REFERENCES] if n not in SERVER_DICT]
    if missing:
        print(f"Not running: {', '.join(missing)}", file=sys.stderr)
        return 2
    servers = [SERVER_DICT[n] for n in [*UNI_SERVERS, *REFERENCES]]
    for s in servers:
        s.timeout = TIMEOUT
    # Containers can take a moment to listen after `docker compose up -d`
    deadline = time.monotonic() + 120

    def answers(server) -> bool:
        try:
            return bool(server.parsed_roundtrip([b"GET / HTTP/1.1\r\nHost: a\r\n\r\n"]))
        except ConnectionRefusedError:
            return False

    for s in servers:
        while not answers(s):
            if time.monotonic() > deadline:
                print(f"{s.name} did not answer a plain GET", file=sys.stderr)
                return 2
            time.sleep(1)

    seeds = lean_inputs(args.lean_cases) + corpus_inputs(args.corpus)
    random.seed(args.seed)
    payloads = list(seeds)
    for _ in range(args.mutations):
        p = [random.choice(seeds)]
        for _ in range(random.randint(1, 3)):
            try:
                p = mutate(p)
            except Exception:  # Garden's parser can fail on a seed (e.g. a fake gzip body)
                break
        payloads.append(b"".join(p))
    payloads = list(dict.fromkeys(payloads))

    known = {(k["server"], decode(k["payload"])) for k in (yaml.safe_load(args.known.read_text()) or [])}

    findings = []  # every Uni server that agrees with no reference
    status_only = 0  # payloads where the only differences are rejection statuses
    status_differences = []  # each Uni server whose rejection status differs from a reference's
    for payload in payloads:
        results = dict(zip((s.name for s in servers), fanout([payload], servers)))
        only_status = False
        for name in UNI_SERVERS:
            uni = SERVER_DICT[name]
            # Uni accepted a request whose name, value, or method has bytes the RFCs forbid
            invalid = any(isinstance(r, HTTPRequest) and not r.is_valid() for r in results[name])
            categories = {ref: categorize_discrepancy(results[name], results[ref], uni, SERVER_DICT[ref]).name for ref in REFERENCES}
            if not invalid and not all(c in FAILING for c in categories.values()):
                if all(c in ("OK", "RESPONSE_DISCREPANCY") for c in categories.values()) and "RESPONSE_DISCREPANCY" in categories.values():
                    only_status = True
                    status_differences.append(
                        {
                            "server": name,
                            "payload": show(payload),
                            # Uni's 501 for a method outside its list is the declared quirk
                            "unknown_method": unknown_method(payload) and describe(results[name]).startswith("501"),
                            "results": {n: describe(results[n]) for n in [name, *REFERENCES]},
                        }
                    )
                continue
            findings.append(
                {
                    "server": name,
                    "payload": show(payload),
                    "categories": categories,
                    "invalid": invalid,
                    "known": (name, payload) in known or (name in DECODES_CONTENT_ENCODING and b"content-encoding" in payload.lower()),
                    "results": {n: describe(results[n]) for n in [name, *REFERENCES]},
                }
            )
        status_only += only_status

    failing = [f for f in findings if not f["known"]]
    args.report.write_text(json.dumps({"payloads": len(payloads), "findings": findings, "status_differences": status_differences}, indent=2))

    print(f"## HTTP Garden: Uni vs. {', '.join(REFERENCES)}\n")
    print(f"- Payloads: {len(payloads)} ({len(seeds)} seeds, {args.mutations} mutations, seed {args.seed})")
    print(f"- Findings: {len(findings)} ({len(failing)} new)")
    expected = sum(1 for d in status_differences if d["unknown_method"])
    print(
        f"- Payloads where only the rejection status differs: {status_only} (not counted);"
        f" {expected} of the server answers are 501 for a method outside Uni's list"
    )
    # Group by the statuses each server answered, so a mismatch that repeats shows as one row
    groups: dict[tuple, int] = {}
    for d in status_differences:
        if d["unknown_method"]:
            continue
        key = (d["server"], *(d["results"][n] for n in [d["server"], *REFERENCES]))
        groups[key] = groups.get(key, 0) + 1
    if groups:
        print("\n| Server | Uni | " + " | ".join(REFERENCES) + " | Payloads |")
        print("|---|---|" + "---|" * len(REFERENCES) + "---|")
        for key, count in sorted(groups.items(), key=lambda kv: -kv[1]):
            print("| " + " | ".join(c.replace("|", "\\|")[:60] for c in key) + f" | {count} |")
        print()
    print("- Payloads are cut to 160 characters here; report.json in the artifact has them in full\n")
    if findings:
        print("| Server | Payload | Uni | " + " | ".join(REFERENCES) + " | Status |")
        print("|---|---|---|" + "---|" * len(REFERENCES) + "---|")
        for f in findings:
            status = "known" if f["known"] else "**new**"
            if f["invalid"]:
                status += ", invalid request accepted"
            cells = [f["results"][n] for n in [f["server"], *REFERENCES]]
            cells = [c.replace("|", "\\|")[:120] for c in cells]
            print(f"| {f['server']} | `{f['payload'][:160].replace('|', '\\|')}` | " + " | ".join(cells) + f" | {status} |")
    return 1 if failing else 0


if __name__ == "__main__":
    sys.exit(main())
