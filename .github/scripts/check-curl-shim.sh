#!/usr/bin/env bash
#
# Asserts that uni_curl_shim.c compiles to an object referencing no libcurl symbol.
#
# Scala Native compiles that file into every downstream binary, whether or not the project uses
# CurlBindings. If its object names `curl_easy_setopt`, a project that never links libcurl fails to
# link (issue #622, adr/2026-07-06-curl-shim-weak-linking.md).
#
# No Scala Native job in this repo can catch that, on any OS: build.sbt passes -lcurl unconditionally,
# so every uni native binary resolves those symbols and links happily. Only a consumer without -lcurl
# breaks. Inspecting the object directly is what stands in for that consumer.
#
# The other way this file has broken — failing to compile at all, as when v2026.1.17 included the
# POSIX-only <dlfcn.h> — is covered by the "Scala Native (Windows)" job, which builds it for real.
set -euo pipefail

shim="uni/.native/src/main/resources/scala-native/uni_curl_shim.c"
obj="$(mktemp -d)/uni_curl_shim.o"

echo "== Compiling ${shim} with $(clang --version | head -1)"
clang -c "${shim}" -o "${obj}" -Wall -Wextra -Werror

echo "== Undefined symbols"
nm -u "${obj}" | tee "${obj}.undefined"

if grep -qi 'curl_easy' "${obj}.undefined"; then
  echo
  echo "ERROR: the shim object references libcurl symbols. Downstream Scala Native projects that do"
  echo "not use CurlBindings never link -lcurl, so their build fails with 'undefined reference to"
  echo "curl_easy_setopt'. Resolve the symbols at runtime instead — see issue #622 and"
  echo "adr/2026-07-06-curl-shim-weak-linking.md."
  exit 1
fi

echo
echo "OK: compiles cleanly and references no libcurl symbol."
