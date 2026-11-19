# HTTP Garden echo origin

Differential tests for Uni's HTTP/1.1 request parsers with [HTTP Garden](https://github.com/narfindustries/http-garden). Garden sends the same raw bytes to many servers and compares how each one read them. A server that accepts a request others reject, reads a different body, or splits a byte stream into a different number of requests is the root of request smuggling.

`GardenEcho` is a Garden origin: it answers every request with a 200 whose body is a JSON echo of the parsed method, target, headers, and body. It runs three ways from the same handler:

| Garden name | Server | How it starts |
|-------------|--------|---------------|
| `uni_jvm` | The built-in JVM server (JDK 24+) | `java` with the runtime classpath minus the Netty jars |
| `uni_native` | Scala Native's server | the `nativeLink` binary |
| `uni_netty` | uni-netty | `java` with the full runtime classpath |

The references are Garden's hyper, Netty, and Apache httpd origins, which all serve plain HTTP.

## Run it

The [HTTP Garden workflow](../../.github/workflows/http-garden.yml) runs it weekly on main, when dispatched (with the number of mutations and the seed as inputs), and on pull requests that change this folder. The job summary lists each finding, and `report.json` plus the server logs are uploaded as an artifact.

On a Linux machine with Docker, Python 3.13+ (with `docker`, `pyyaml`, and `tqdm`), JDK 25, and libgc:

```bash
examples/http-garden/garden/run.sh --mutations 500 --seed 1
```

`run.sh` builds the echo app, clones Garden at a pinned commit into `garden/out`, builds and starts the reference servers in Docker, starts the three Uni servers on ports 18081–18083, and runs `garden/uni_garden.py`.

## What it sends and what fails

- Seeds: the request inputs of the Lean-generated cases in `uni/.jvm-native/src/test/scala/wvlet/uni/http/HttpReaderSpecCases.scala`, which the Lean spec CI job keeps in sync with `spec/lean/UniSpec/HttpReader.lean`, plus the smuggling patterns in `garden/corpus.txt`.
- Mutations: Garden's own mutators applied to random seeds, from a fixed seed, so a run is repeatable.
- A finding is a payload where a Uni server agrees with none of the references: one accepts while the other rejects, the parsed requests differ, the request split differs, or Uni accepts a request with bytes the RFCs forbid. Differences only in the rejection status (Uni's 501, 505, or 431 against a 400) are counted but not listed.
- New findings for `uni_jvm` and `uni_native` fail the run. `uni_netty` findings are reported only.
- Known differences, which never fail the run:
  - `uni_jvm` always decodes gzip/deflate request bodies, so any payload with `Content-Encoding` differs.
  - Entries in `garden/known-differences.yml`, each with a reason. Fix a real bug in its own PR rather than listing it.
- `garden/uni_garden.py` declares how Uni differs from a plain echo in harmless ways (Garden's quirks): a missing `Host` is allowed, the echo has no HTTP version, methods outside the nine standard ones get 501, and a chunked body is echoed with `Content-Length` instead of `Transfer-Encoding`.

## License

HTTP Garden is GPL-3.0. It is cloned when the job runs, and none of its code is kept in this repository. `uni_garden.py` is copied into the clone and run there, as test tooling that is never published.
