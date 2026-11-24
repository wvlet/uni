#!/usr/bin/env bash
# Run Uni's HTTP servers against HTTP Garden reference servers (Linux with Docker only).
#
#   examples/http-garden/garden/run.sh [--mutations N] [--seed S]
#
# Builds the echo app (built-in JVM server, uni-netty, Scala Native), clones HTTP Garden at a
# pinned commit into garden/out, builds and starts the reference servers in Docker, then runs
# uni_garden.py. Prints a Markdown summary and writes out/report.json. Exits 1 on a new finding.
#
# Needs: Docker with compose, Python 3.13+ with docker, pyyaml, and tqdm, JDK 25.
set -euo pipefail

# HTTP Garden is GPL-3.0; it is cloned at run time and none of its code is kept in this repository
GARDEN_REPO="https://github.com/narfindustries/http-garden.git"
GARDEN_COMMIT="b417e806c1b15e8ea0b9312f81a91fbdcbc7a83e"
REFERENCES=(hyper netty apache_httpd)

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${HERE}/../../.." && pwd)"
OUT="${HERE}/out"
GARDEN="${OUT}/http-garden" # compose names the network after this folder: http-garden_default
PYTHON="${PYTHON:-python3}"
mkdir -p "${OUT}"

# Build and Docker output goes to stderr, so stdout holds only the Markdown summary
cd "${ROOT}"
./sbt -batch exampleHttpGardenJVM/stageGardenJars exampleHttpGardenNative/nativeLink >&2
native="$(find target/out -type f -perm -u+x \
  -path '*/uni-example-http-garden/uni-example-http-garden' 2>/dev/null | head -1 || true)"
if [[ -z "${native}" ]]; then
  echo "ERROR: exampleHttpGardenNative/nativeLink produced no binary under target/out" >&2
  exit 1
fi
netty_cp="$(ls "${OUT}"/lib/*.jar | paste -sd: -)"
# Without the Netty jars, Http.server falls back to uni's built-in server
builtin_cp="$(ls "${OUT}"/lib/*.jar | grep -v -E '/(netty-|uni-netty)' | paste -sd: -)"

if [[ ! -d "${GARDEN}/.git" ]]; then
  git clone --quiet "${GARDEN_REPO}" "${GARDEN}" >&2
fi
git -C "${GARDEN}" fetch --quiet origin "${GARDEN_COMMIT}"
git -C "${GARDEN}" checkout --quiet --force "${GARDEN_COMMIT}"

pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "${pid}" 2> /dev/null || true; done
  (cd "${GARDEN}" && docker compose down --remove-orphans > /dev/null 2>&1) || true
}
trap cleanup EXIT

start() { # name port command...
  local name="$1" port="$2"
  shift 2
  "$@" "${port}" > "${OUT}/${name}.log" 2>&1 &
  pids+=($!)
}
# Ports match UNI_SERVERS in uni_garden.py
start uni_jvm 18081 java -cp "${builtin_cp}" example.garden.GardenEcho
start uni_native 18082 "${native}"
start uni_netty 18083 java -cp "${netty_cp}" example.garden.GardenEcho

(
  exec >&2
  cd "${GARDEN}"
  docker pull --quiet debian:trixie-slim
  docker build --quiet ./images/http-garden-soil -t http-garden-soil
  docker compose build "${REFERENCES[@]}"
  docker compose up --detach "${REFERENCES[@]}"
)

for name in uni_jvm uni_native uni_netty; do
  for _ in $(seq 60); do
    grep -q '^ready' "${OUT}/${name}.log" && break
    sleep 1
  done
  grep -q '^ready' "${OUT}/${name}.log" || { echo "ERROR: ${name} did not start" >&2; cat "${OUT}/${name}.log" >&2; exit 1; }
done

# Garden's modules read their config relative to the running script, so run from its tools/
cp "${HERE}/uni_garden.py" "${GARDEN}/tools/uni_garden.py"
"${PYTHON}" "${GARDEN}/tools/uni_garden.py" \
  --lean-cases "${ROOT}/uni/.jvm-native/src/test/scala/wvlet/uni/http/HttpReaderSpecCases.scala" \
  --corpus "${HERE}/corpus.txt" \
  --known "${HERE}/known-differences.yml" \
  --report "${OUT}/report.json" \
  "$@"
