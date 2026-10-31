#!/usr/bin/env python3
"""Benchmark the rpc-native example: the same TodoApi RPC server as a native binary and on the JVM.

For each target it measures:
  - launch -> first successful RPC response (median and min of --runs fresh processes)
  - RSS right after that first call
  - throughput and p50/p99 latency under load (oha, or wrk if oha is missing), then RSS after load
Container targets (--native-image/--jvm-image) get image size and container cold start only.

It prints a Markdown table with the machine, JDK, Scala Native mode, and load settings.
Standard library only. Run bench/build.sh first; see bench/README.md.
"""

import argparse
import http.client
import json
import os
import platform
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time

SERVICE = "/example.rpc.TodoApi"
ADD_BODY = b'{"request":{"title":"hello"}}'
LIST_BODY = b'{"request":{}}'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def rpc_call(port, method, body, timeout=2):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        conn.request("POST", f"{SERVICE}/{method}", body, {"Content-Type": "application/json"})
        res = conn.getresponse()
        data = res.read()
        if res.status != 200:
            raise RuntimeError(f"HTTP {res.status}: {data[:200]!r}")
        return data
    finally:
        conn.close()


def wait_first_response(port, proc=None, deadline_s=60):
    """Poll until the add RPC succeeds and return its response body."""
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        if proc is not None and proc.poll() is not None:
            raise RuntimeError(f"server exited with {proc.returncode}")
        try:
            return rpc_call(port, "add", ADD_BODY, timeout=1)
        except (OSError, http.client.HTTPException):
            time.sleep(0.001)
    raise RuntimeError("server did not answer in time")


def rss_mb(pid):
    status = f"/proc/{pid}/status"
    if os.path.exists(status):
        with open(status) as f:
            m = re.search(r"VmRSS:\s+(\d+) kB", f.read())
            return int(m.group(1)) / 1024
    return int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(pid)]).strip()) / 1024


def pinned(cmd, cpus):
    """Prefix cmd with taskset (Linux) so the server and the load tool use separate cores."""
    if cpus and shutil.which("taskset"):
        return ["taskset", "-c", cpus] + cmd
    return cmd


def start(cmd, cpus):
    t0 = time.perf_counter()
    proc = subprocess.Popen(pinned(cmd, cpus), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return t0, proc


def stop(proc):
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def load_tool():
    if shutil.which("oha"):
        return "oha"
    if shutil.which("wrk"):
        return "wrk"
    sys.exit("Install oha (https://github.com/hatoo/oha) or wrk for the load test")


def run_load(tool, port, args, duration):
    url = f"http://127.0.0.1:{port}{SERVICE}/list"
    if tool == "oha":
        cmd = ["oha", "--no-tui", "--output-format", "json", "-z", duration,
               "-c", str(args.connections), "-m", "POST",
               "-H", "Content-Type: application/json", "-d", LIST_BODY.decode(), url]
        out = json.loads(subprocess.run(pinned(cmd, args.client_cpus), capture_output=True,
                                        text=True, check=True).stdout)
        kinds = {f"HTTP {k}": v for k, v in out.get("statusCodeDistribution", {}).items()
                 if k != "200"}
        # Requests still in flight when -z expires are reported as aborted; they are not failures
        kinds.update({k: v for k, v in out.get("errorDistribution", {}).items()
                      if k != "aborted due to deadline"})
        lat = out.get("latencyPercentiles") or {}

        def ms(key):
            # None when no request succeeded
            return lat[key] * 1000 if lat.get(key) is not None else None

        return {"rps": out["summary"]["requestsPerSec"], "p50_ms": ms("p50"), "p99_ms": ms("p99"),
                "errors": sum(kinds.values()), "error_kinds": kinds}
    # wrk needs a Lua script to send a POST body
    with tempfile.NamedTemporaryFile("w", suffix=".lua", delete=False) as f:
        f.write('wrk.method = "POST"\n'
                f"wrk.body = '{LIST_BODY.decode()}'\n"
                'wrk.headers["Content-Type"] = "application/json"\n')
        script = f.name
    threads = min(args.connections, os.cpu_count() or 1)
    cmd = ["wrk", "--latency", "-t", str(threads), "-c", str(args.connections), "-d", duration,
           "-s", script, url]
    try:
        out = subprocess.run(pinned(cmd, args.client_cpus), capture_output=True, text=True,
                             check=True).stdout
    finally:
        os.unlink(script)

    def ms(label):
        m = re.search(rf"^\s+{label}%\s+([\d.]+)(us|ms|s)\s*$", out, re.M)
        return float(m.group(1)) * {"us": 0.001, "ms": 1, "s": 1000}[m.group(2)]

    errors = re.search(r"Non-2xx or 3xx responses: (\d+)", out)
    socket_errors = re.search(r"Socket errors: (.+)", out)
    kinds = {"non-2xx": int(errors.group(1))} if errors else {}
    if socket_errors:
        kinds["socket errors"] = socket_errors.group(1)
    return {"rps": float(re.search(r"Requests/sec:\s+([\d.]+)", out).group(1)),
            "p50_ms": ms("50"), "p99_ms": ms("99"),
            "errors": int(errors.group(1)) if errors else 0, "error_kinds": kinds}


def bench_process(name, cmd, args, tool):
    startups, rss_first = [], []
    for _ in range(args.runs):
        port = free_port()
        t0, proc = start(cmd + [str(port)], args.server_cpus)
        try:
            wait_first_response(port, proc)
            startups.append((time.perf_counter() - t0) * 1000)
            rss_first.append(rss_mb(proc.pid))
        finally:
            stop(proc)
    res = {"name": name, "startup_ms": statistics.median(startups), "startup_min_ms": min(startups),
           "rss_first_mb": statistics.median(rss_first)}
    if not args.no_load:
        port = free_port()
        _, proc = start(cmd + [str(port)], args.server_cpus)
        try:
            wait_first_response(port, proc)
            run_load(tool, port, args, args.warmup)
            res.update(run_load(tool, port, args, args.duration))
            res["rss_load_mb"] = rss_mb(proc.pid)
        finally:
            stop(proc)
    print(f"  {name}: {json.dumps({k: round(v, 1) if isinstance(v, float) else v for k, v in res.items()})}",
          file=sys.stderr)
    return res


def bench_container(name, image, args):
    size = int(subprocess.check_output(["docker", "image", "inspect", "-f", "{{.Size}}", image]))
    startups = []
    for i in range(args.runs):
        port = free_port()
        cname = f"rpc-bench-{os.getpid()}-{i}"
        t0 = time.perf_counter()
        subprocess.run(["docker", "run", "-d", "--rm", "--name", cname, "-p", f"127.0.0.1:{port}:8080",
                        image], check=True, stdout=subprocess.DEVNULL)
        try:
            wait_first_response(port)
            startups.append((time.perf_counter() - t0) * 1000)
        finally:
            subprocess.run(["docker", "rm", "-f", cname], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
    res = {"name": name, "image_mb": size / 1e6, "startup_ms": statistics.median(startups),
           "startup_min_ms": min(startups)}
    print(f"  {name}: {json.dumps({k: round(v, 1) if isinstance(v, float) else v for k, v in res.items()})}",
          file=sys.stderr)
    return res


def first_line(cmd):
    try:
        out = subprocess.run(cmd, capture_output=True, text=True)
        return (out.stdout + out.stderr).strip().splitlines()[0]
    except (OSError, IndexError):
        return "unknown"


def cpu_name():
    if platform.system() == "Darwin":
        return first_line(["sysctl", "-n", "machdep.cpu.brand_string"])
    try:
        with open("/proc/cpuinfo") as f:
            m = re.search(r"^model name\s*:\s*(.+)$", f.read(), re.M)
            if m:
                return m.group(1)
    except OSError:
        pass
    # arm64 Linux has no "model name" line; lscpu reports e.g. Neoverse-N2
    m = re.search(r"Model name:\s*(.+)", subprocess.run(["lscpu"], capture_output=True, text=True).stdout)
    return m.group(1).strip() if m else platform.processor()


def linked_libs(binary):
    cmd = ["otool", "-L", binary] if platform.system() == "Darwin" else ["ldd", binary]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout.splitlines()
    libs = [os.path.basename(l.split()[0]) for l in out if l.startswith(("\t", " "))]
    return ", ".join(libs) or "none (static)"


def read_out(name, default="unknown"):
    path = os.path.join(OUT, name)
    return open(path).read().strip() if os.path.exists(path) else default


def fmt(v, digits=0):
    return "–" if v is None else f"{v:,.{digits}f}"


def report(args, tool, procs, containers):
    jars = [j for j in read_out("jvm.cp", "").split(os.pathsep) if j]
    jar_mb = sum(os.path.getsize(j) for j in jars if os.path.exists(j)) / 1e6
    lines = ["## Typed RPC server: Scala Native vs. JVM", ""]
    lines += ["| | " + " | ".join(r["name"] for r in procs) + " |",
              "|---|" + "---|" * len(procs)]
    rows = [(f"launch → first RPC response, median of {args.runs} (ms)", "startup_ms", 0),
            ("launch → first RPC response, min (ms)", "startup_min_ms", 0),
            ("RSS after first call (MB)", "rss_first_mb", 0)]
    if not args.no_load:
        rows += [("RSS after load (MB)", "rss_load_mb", 0), ("throughput (req/s)", "rps", 0),
                 ("latency p50 (ms)", "p50_ms", 2), ("latency p99 (ms)", "p99_ms", 2),
                 ("failed requests", "errors", 0)]
    for label, key, d in rows:
        lines.append(f"| {label} | " + " | ".join(fmt(r.get(key), d) for r in procs) + " |")
    native_mb = os.path.getsize(args.native) / 1e6
    lines.append(f"| artifact | one {native_mb:.1f} MB binary | "
                 + " | ".join(f"JDK + {len(jars)} jars ({jar_mb:.1f} MB)" for _ in procs[1:]) + " |")
    if containers:
        lines += ["", "| container | image size (MB) | cold start, median of "
                  f"{args.runs} (ms) | min (ms) |", "|---|---|---|---|"]
        for c in containers:
            lines.append(f"| {c['name']} | {c['image_mb']:.1f} | {fmt(c['startup_ms'])} | "
                         f"{fmt(c['startup_min_ms'])} |")
    load = tool and (f"{first_line([tool, '--version'])}, POST {SERVICE}/list, "
            f"{args.connections} connections, keep-alive, {args.warmup} warm-up then {args.duration}")
    lines += ["", "Environment:", "",
              f"- OS: {platform.system()} {platform.release()} ({platform.machine()}), "
              f"{os.cpu_count()} CPUs",
              f"- CPU: {cpu_name()}",
              f"- JDK: {first_line([args.java, '-version'])}",
              f"- Scala Native: {read_out('native-mode.txt')}; links {linked_libs(args.native)}",
              f"- JVM small heap options: `{args.jvm_small_opts}`",
              f"- Load: {'skipped' if args.no_load else load}",
              *[f"- Failed requests ({r['name']}): {r['error_kinds']}" for r in procs
                if r.get("error_kinds")],
              f"- CPU sets: server {args.server_cpus or 'any'}, load tool {args.client_cpus or 'any'}",
              "- Launch → first response: from process spawn to the first successful "
              f"`{SERVICE}/add` call, polled every 1 ms; each run is a fresh process."]
    if containers:
        lines.append(f"- Container cold start: from `docker run` to the first successful call "
                     f"({first_line(['docker', '--version'])})")
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--native", default=os.path.join(OUT, "rpc-native"), help="native server binary")
    p.add_argument("--java", default=os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                   if "JAVA_HOME" in os.environ else "java")
    p.add_argument("--jvm-small-opts", default="-Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1",
                   help="JVM options for the small-heap run")
    p.add_argument("--runs", type=int, default=7, help="fresh launches per target")
    p.add_argument("--connections", "-c", type=int, default=16)
    p.add_argument("--duration", default="10s")
    p.add_argument("--warmup", default="5s")
    p.add_argument("--no-load", action="store_true", help="measure startup and memory only")
    p.add_argument("--server-cpus", help="taskset CPU list for the server, e.g. 0-1 (Linux)")
    p.add_argument("--client-cpus", help="taskset CPU list for the load tool, e.g. 2-3 (Linux)")
    p.add_argument("--native-image", help="container image of the native server")
    p.add_argument("--jvm-image", help="container image of the JVM server")
    p.add_argument("--json", help="also write raw results to this file")
    args = p.parse_args()

    cp = read_out("jvm.cp", None)
    if not os.path.exists(args.native) or cp is None:
        sys.exit("Run examples/rpc-native/bench/build.sh first")
    tool = None if args.no_load else load_tool()
    jvm = [args.java, "-cp", cp]
    targets = [("Native", [args.native]), ("JVM defaults", jvm + ["example.rpc.Main"]),
               ("JVM small heap", jvm[:1] + args.jvm_small_opts.split() + jvm[1:]
                + ["example.rpc.Main"])]
    print("Benchmarking (one line per target)...", file=sys.stderr)
    procs = [bench_process(name, cmd, args, tool) for name, cmd in targets]
    containers = [bench_container(name, image, args)
                  for name, image in [("Native", args.native_image), ("JVM", args.jvm_image)] if image]
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"processes": procs, "containers": containers}, f, indent=2)
    print(report(args, tool, procs, containers))


if __name__ == "__main__":
    main()
