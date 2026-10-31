#!/usr/bin/env bash
# Build both servers for bench.py into bench/out:
#   out/rpc-native       the Scala Native binary
#   out/libgc.so.1       the Boehm GC library it links (Linux only, for the container)
#   out/lib/*.jar        the JVM server's runtime classpath (also the JVM container's contents)
#   out/jvm.cp           that classpath, for `java -cp`
#   out/native-mode.txt  the Native build mode and LTO setting
set -euo pipefail

BENCH="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${BENCH}/../../.." && pwd)"
OUT="${BENCH}/out"
cd "${ROOT}"

rm -rf "${OUT}"
mkdir -p "${OUT}"

# Show sbt's output (errors included) while keeping it for the nativeConfig lines below
log="$(./sbt -batch exampleRpcNativeNative/nativeLink exampleRpcNativeJVM/stageBenchJars \
  "print exampleRpcNativeNative/nativeConfig" | sed 's/\x1b\[[0-9;]*m//g' | tee /dev/stderr)"

binary="$(find target/out -type f -perm -u+x -path '*/uni-example-rpc-native/uni-example-rpc-native' \
  2>/dev/null | head -1 || true)"
if [[ -z "${binary}" ]]; then
  echo "ERROR: exampleRpcNativeNative/nativeLink produced no binary under target/out" >&2
  exit 1
fi
cp "${binary}" "${OUT}/rpc-native"
# The example links the Boehm GC (libgc); copy the library the binary resolves to for the container
if command -v ldd > /dev/null; then
  libgc="$(ldd "${OUT}/rpc-native" | awk '/libgc\.so/ {print $3}')"
  if [[ -z "${libgc}" ]]; then
    echo "ERROR: ${OUT}/rpc-native does not resolve libgc; install libgc-dev" >&2
    exit 1
  fi
  cp -L "${libgc}" "${OUT}/libgc.so.1"
fi

(cd "${OUT}/lib" && ls *.jar | sed "s|^|${OUT}/lib/|" | paste -sd: -) > "${OUT}/jvm.cp"

# e.g. "mode: release-full, LTO: full, GC: boehm"
printf '%s\n' "${log}" | { grep -E '^ - (mode|LTO|GC):' || true; } | sed -E 's/^ - //; s/:[[:space:]]+/: /' \
  | sort -r | paste -sd, - | sed 's/,/, /g' > "${OUT}/native-mode.txt"

echo "native: ${OUT}/rpc-native ($(wc -c < "${OUT}/rpc-native" | tr -d ' ') bytes; $(cat "${OUT}/native-mode.txt"))"
echo "jvm:    $(ls "${OUT}/lib" | wc -l | tr -d ' ') jars in ${OUT}/lib"
