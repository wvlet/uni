#!/usr/bin/env bash
#
# Assert that uni_curl_shim.c compiles to an object referencing no libcurl symbol.
#
# Scala Native compiles this file into every downstream binary, whether or not the project uses
# CurlBindings. A project that never links libcurl then fails to link with "undefined reference to
# curl_easy_setopt" if the object names one (issue #622, adr/2026-07-06-curl-shim-weak-linking.md).
#
# No Scala Native job in this repo can catch that: build.sbt passes -lcurl unconditionally, so every
# uni native binary resolves those symbols and links happily. Only a consumer without -lcurl breaks.
# Inspecting the object directly is what stands in for that consumer.
set -euo pipefail

shim="uni/.native/src/main/resources/scala-native/uni_curl_shim.c"
obj="$(mktemp -d)/uni_curl_shim.o"

echo "== Compiling ${shim} with $(clang --version | head -1)"
clang -c "${shim}" -o "${obj}" -Wall -Wextra -Werror

echo "== Undefined symbols"
nm -u "${obj}"

if nm -u "${obj}" | grep -qi 'curl_easy'; then
  echo
  echo "ERROR: the shim object references libcurl symbols. Downstream Scala Native projects that do"
  echo "not use CurlBindings never link -lcurl, so their build fails with 'undefined reference to"
  echo "curl_easy_setopt'. Resolve the symbols at runtime instead — see issue #622 and"
  echo "adr/2026-07-06-curl-shim-weak-linking.md."
  exit 1
fi

echo
echo "OK: references no libcurl symbol."
