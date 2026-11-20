#!/usr/bin/env bash
# Start one of the comparison servers: run.sh <node|python|ruby|rust> <processes> <port>
# Each serves POST /example.rpc.TodoApi/list with ITEMS todos (default 1), the same JSON as the
# rpc-native example, gzipped when 1 KB or larger and the client accepts gzip. See README.md.
set -euo pipefail
d=$(cd "$(dirname "$0")" && pwd); kind=$1; procs=$2; port=$3
case $kind in
  node) WORKERS=$procs exec node "$d/server.js" "$port" ;;
  python) cd "$d" && exec uvicorn app:app --host 127.0.0.1 --port "$port" --workers "$procs" \
            --loop uvloop --http httptools --no-access-log --log-level warning ;;
  ruby) cd "$d"
        if [ "$procs" -gt 1 ]; then w=(-w "$procs" -t 1:1); else w=(-w 0 -t 16:16); fi
        RUBYOPT=--yjit exec puma -q -b "tcp://127.0.0.1:$port" "${w[@]}" -e production config.ru ;;
  rust) exec "$d/rust/target/release/todo-bench" "$port" ;;
  *) echo "usage: run.sh <node|python|ruby|rust> <processes> <port>" >&2; exit 1 ;;
esac
