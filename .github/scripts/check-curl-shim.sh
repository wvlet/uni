#!/usr/bin/env bash
#
# Guards uni_curl_shim.c, which Scala Native compiles into every downstream binary on every platform
# it supports. Two things can go wrong, and both have:
#
#   1. It fails to compile. v2026.1.17 included <dlfcn.h>, which the MSVC toolchain does not ship,
#      breaking every downstream Windows Scala Native build.
#   2. Its object references a libcurl symbol, which breaks the link of downstream projects that
#      never pull in -lcurl (issue #622, adr/2026-07-06-curl-shim-weak-linking.md).
#
# No Scala Native job in this repo catches (2): build.sbt passes -lcurl unconditionally, so every uni
# native binary resolves those symbols and links happily. Only a consumer without -lcurl breaks, and
# inspecting the object directly is what stands in for that consumer.
#
# Nor can one catch (1) on Windows: uni's NativeServer uses POSIX poll(), which Scala Native's
# posixlib only builds on unix/Apple, so uni's native test binary cannot link on Windows at all.
# Compiling this one file standalone with clang is the coverage that is available there.
set -euo pipefail

shim="uni/.native/src/main/resources/scala-native/uni_curl_shim.c"
obj="$(mktemp -d)/uni_curl_shim.o"

echo "== Compiling ${shim} with $(clang --version | head -1)"
clang -c "${shim}" -o "${obj}" -Wall -Wextra -Werror

echo "== Undefined symbols"
if [[ "${RUNNER_OS:-}" == "Windows" ]]; then
  nm_undefined=(llvm-nm --undefined-only)
else
  nm_undefined=(nm -u)
fi
"${nm_undefined[@]}" "${obj}" | tee "${obj}.undefined"

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
