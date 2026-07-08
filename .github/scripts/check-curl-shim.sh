#!/usr/bin/env bash
#
# Guards uni_curl_shim.c, which Scala Native compiles into every downstream binary on every platform
# it supports — including Windows, where uni itself has no Scala Native build to catch breakage.
#
# Two things can go wrong, and both have:
#   1. The file fails to compile at all (v2026.1.17 included <dlfcn.h>, which MSVC does not ship).
#   2. The object file references a libcurl symbol, which breaks the link of downstream projects that
#      never pull in -lcurl. See issue #622 and adr/2026-07-06-curl-shim-weak-linking.md.
#
# Compiling the file standalone checks both, and needs no Scala Native toolchain to do it.
set -euo pipefail

shim="uni/.native/src/main/resources/scala-native/uni_curl_shim.c"
obj="uni_curl_shim.o"

echo "== Compiling ${shim} with $(clang --version | head -1)"
clang -c "${shim}" -o "${obj}" -Wall -Wextra -Werror

echo "== Undefined symbols in ${obj}"
case "${RUNNER_OS:-}" in
  Windows) llvm-nm --undefined-only "${obj}" | tee undefined-symbols.txt ;;
  *) nm -u "${obj}" | tee undefined-symbols.txt ;;
esac

if grep -qi 'curl_easy' undefined-symbols.txt; then
  echo
  echo "ERROR: ${obj} references libcurl symbols. Downstream Scala Native projects that do not use"
  echo "CurlBindings never link -lcurl, so their build fails with 'undefined reference to"
  echo "curl_easy_setopt'. Resolve the symbols at runtime instead — see issue #622 and"
  echo "adr/2026-07-06-curl-shim-weak-linking.md."
  exit 1
fi

echo
echo "OK: compiles cleanly and references no libcurl symbols."
