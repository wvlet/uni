#!/usr/bin/env bash
# Checks the staged @wvlet/uni package (./sbt npm/npmPackage) as a user would get it: installs it
# into node_modules from its packed tarball, runs the Node smoke test, and type-checks a TS
# consumer against parquet.d.ts.
#
# Usage: uni-npm/npm-test/run.sh <staged package dir>
set -euo pipefail

pkg_dir=$(cd "$1" && pwd)
here=$(cd "$(dirname "$0")" && pwd)

work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

# Pack exactly what `npm publish` would upload, then install that tarball
(cd "${pkg_dir}" && npm pack --pack-destination "${work}" --silent >/dev/null)
mkdir -p "${work}/node_modules/@wvlet/uni"
tar -xzf "${work}"/wvlet-uni-*.tgz -C "${work}/node_modules/@wvlet/uni" --strip-components=1
cp "${here}/parquet.test.mjs" "${here}/consumer.ts" "${work}/"

(cd "${work}" && node --test parquet.test.mjs)

cat >"${work}/tsconfig.json" <<'EOF'
{
  "compilerOptions": {
    "target": "es2022",
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "lib": ["es2022", "dom"],
    "strict": true,
    "noEmit": true,
    "skipLibCheck": false,
    "types": []
  },
  "files": ["consumer.ts"]
}
EOF
# Pinned, and kept out of the docs dependencies so it does not change their peer resolution
(cd "${work}" && npx --yes -p typescript@5.9.3 tsc -p tsconfig.json)
echo "npm package checks passed"
