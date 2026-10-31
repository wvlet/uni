#!/usr/bin/env bash
# Build both servers for bench.py into bench/out:
#   out/rpc-native       the Scala Native binary
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

log="$(./sbt -batch exampleRpcNativeNative/nativeLink exampleRpcNativeJVM/stageBenchJars \
  "print exampleRpcNativeNative/nativeConfig" | sed 's/\x1b\[[0-9;]*m//g')"

binary="$(find target/out -type f -perm -u+x -path '*/uni-example-rpc-native/uni-example-rpc-native' | head -1)"
cp "${binary}" "${OUT}/rpc-native"

(cd "${OUT}/lib" && ls *.jar | sed "s|^|${OUT}/lib/|" | paste -sd: -) > "${OUT}/jvm.cp"

# e.g. "mode: release-full, LTO: full, GC: immix"
printf '%s\n' "${log}" | grep -E '^ - (mode|LTO|GC):' | sed -E 's/^ - //; s/:[[:space:]]+/: /' \
  | sort -r | paste -sd, - | sed 's/,/, /g' > "${OUT}/native-mode.txt"

echo "native: ${OUT}/rpc-native ($(wc -c < "${OUT}/rpc-native" | tr -d ' ') bytes; $(cat "${OUT}/native-mode.txt"))"
echo "jvm:    $(ls "${OUT}/lib" | wc -l | tr -d ' ') jars in ${OUT}/lib"
