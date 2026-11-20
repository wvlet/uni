#!/usr/bin/env bash
# Read benchmark: uni-parquet vs. parquet-java on the JVM, and uni-parquet on Node.js and Scala
# Native, over the same files.
#
#   examples/parquet-bench/run.sh [rows] [-- bench options]
#
# rows (default 1000000) is the size of the lineitem table; the files are regenerated when it
# changes. Bench options: --warmup N, --iterations N, --filter TEXT (a reader or file name).
# Set PLATFORMS to a subset of "jvm js native" to skip platforms.
set -euo pipefail

BENCH="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${BENCH}/../.." && pwd)"
OUT="${BENCH}/out"
DATA="${OUT}/data"
ROWS="${1:-1000000}"
shift || true
[[ "${1:-}" == "--" ]] && shift
PLATFORMS="${PLATFORMS:-jvm js native}"
cd "${ROOT}"

./sbt -batch exampleParquetBenchJVM/stageBenchJars exampleParquetBenchJS/fullLinkJS \
  exampleParquetBenchNative/nativeLink
CP="${OUT}/lib/*"
JAVA_OPTS=(-Xmx4g --enable-native-access=ALL-UNNAMED)

if [[ "$(cat "${DATA}/rows.txt" 2> /dev/null || true)" != "${ROWS}" ]]; then
  java "${JAVA_OPTS[@]}" -cp "${CP}" example.parquetbench.DataGen "${DATA}" "${ROWS}"
  echo "${ROWS}" > "${DATA}/rows.txt"
fi

for p in ${PLATFORMS}; do
  echo
  case "${p}" in
    jvm)
      echo "## JVM ($(java -version 2>&1 | head -1))"
      java "${JAVA_OPTS[@]}" -cp "${CP}" example.parquetbench.JvmBench "${DATA}" "$@"
      ;;
    js)
      echo "## Node.js $(node --version)"
      node "$(find target/out/sjs1 -path '*uni-example-parquet-bench-opt/main.js' | head -1)" \
        "${DATA}" "$@"
      ;;
    native)
      echo "## Scala Native"
      "$(find target/out/native0.5 -type f -perm -u+x \
        -path '*/uni-example-parquet-bench/uni-example-parquet-bench' | head -1)" "${DATA}" "$@"
      ;;
  esac
done
