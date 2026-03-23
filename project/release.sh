#!/usr/bin/env bash
set -e

# Release script for uni
# Versioning scheme: YYYY.(milestone).patch

current_branch=$(git rev-parse --abbrev-ref HEAD)
if [ "${current_branch}" != "main" ]; then
  echo "release.sh must run on main branch. The current branch is ${current_branch}"
  exit 1
fi

last_tag=$(git describe --tags --abbrev=0)
last_version=${last_tag#v}
echo "last version: ${last_version}"

current_year=$(date +%Y)
IFS='.' read -r year milestone patch <<< "${last_version}"

if [ "${year}" = "${current_year}" ]; then
  patch=$((patch + 1))
else
  milestone=1
  patch=0
fi
default_version="${current_year}.${milestone}.${patch}"

read -p "next version (default: ${default_version})? " next_version
next_version=${next_version:-${default_version}}

set -x
git tag -a -m "uni ${next_version}" "v${next_version}"
git push --follow-tags
