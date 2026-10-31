#!/usr/bin/env bash
#
# Runs the examples/rpc-native binary (built by `exampleRpcNativeNative/nativeLink`), calls it over
# HTTP, and asserts that it links no libcurl, zlib, or OpenSSL.
#
# The example has no Native test binary (its UniTest suite runs on the JVM), so this is what keeps
# the native server from going stale. The library check backs the example README's claim that a
# server-only binary needs no `-lcurl`: dead-code elimination drops Uni's HTTP client when unused.
set -euo pipefail

binary="$(find target/out -type f -perm -u+x -path '*/uni-example-rpc-native/uni-example-rpc-native' \
  2>/dev/null | head -1 || true)"
if [[ -z "${binary}" ]]; then
  echo "ERROR: run exampleRpcNativeNative/nativeLink first"
  exit 1
fi

echo "== Linked libraries of ${binary}"
ldd "${binary}" | tee /tmp/rpc-native.ldd
if grep -Eq 'libcurl|libz\.|libcrypto|libssl' /tmp/rpc-native.ldd; then
  echo "ERROR: the server-only binary links libcurl, zlib, or OpenSSL"
  exit 1
fi

port=18765
"${binary}" "${port}" &
pid=$!
trap 'kill ${pid} 2>/dev/null || true' EXIT

for _ in $(seq 1 100); do
  if out="$(curl -fsS -X POST "http://127.0.0.1:${port}/example.rpc.TodoApi/add" \
    -H 'Content-Type: application/json' -d '{"request":{"title":"ci"}}' 2>/dev/null)"; then
    break
  fi
  sleep 0.1
done
echo "== add -> ${out:-<no response>}"
[[ "${out:-}" == *'"title":"ci"'* ]]

list="$(curl -fsS -X POST "http://127.0.0.1:${port}/example.rpc.TodoApi/list" \
  -H 'Content-Type: application/json' -d '{"request":{}}')"
echo "== list -> ${list}"
[[ "${list}" == *'"title":"ci"'* ]]
