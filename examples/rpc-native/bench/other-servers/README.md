# The rpc-native endpoint in other languages

Minimal servers in Node.js, Python, Ruby, and Rust that answer the same request as the
[rpc-native example](../../): `POST /example.rpc.TodoApi/list` returns a JSON array of todos,
sorted by id, gzipped when the body is 1 KB or larger and the client sends
`Accept-Encoding: gzip`. They give a point of comparison for the numbers in
`docs/blog/2026-11-19-native-server-performance.md`, using each language's common production setup:

| Server | Stack | Processes |
|---|---|---|
| `server.js` | Node.js built-in `http` (`cluster` for more than one process) | `WORKERS` |
| `app.py` | FastAPI on uvicorn with uvloop and httptools | `--workers` |
| `config.ru` | Rack on Puma, Ruby with YJIT | `-w` workers, or one process with 16 threads |
| `rust/` | axum on tokio (a worker thread per core), serde_json, tower-http gzip | one |

Each server starts with `ITEMS` todos (default 1): item 1 is titled `x`, and items 2.. are titled
`todo item number N with some text`, matching what the example holds after the bench adds them.

```bash
# Dependencies: Node.js; pip install fastapi "uvicorn[standard]"; gem install puma; a Rust toolchain
(cd rust && cargo build --release)
ITEMS=51 ./run.sh node 16 8081 &
oha -z 8s -c 16 -m POST -d '{"request":{}}' -T application/json \
  http://127.0.0.1:8081/example.rpc.TodoApi/list
```

These are small benchmark servers, not examples of how to build an app in each language.
