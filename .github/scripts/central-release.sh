#!/usr/bin/env bash
# Upload a Maven Central Portal bundle and wait until Central has published it.
#
# Usage: central-release.sh BUNDLE_ZIP DEPLOYMENT_NAME
# Env:   SONATYPE_USERNAME, SONATYPE_PASSWORD (a Central Portal user token)
#
# The bundle holds the exact files already on https://repo.wvlet.org/maven2, so Central and the
# wvlet repo serve byte-identical artifacts and signatures.
set -euo pipefail

bundle="$1"
name="$2"
api="https://central.sonatype.com/api/v1/publisher"
auth="Authorization: Bearer $(printf '%s:%s' "${SONATYPE_USERNAME}" "${SONATYPE_PASSWORD}" | base64 | tr -d '\n')"

if ! deployment_id=$(curl --fail-with-body -sS -X POST -H "${auth}" \
  --form "bundle=@${bundle}" \
  "${api}/upload?name=${name}&publishingType=AUTOMATIC"); then
  echo "Upload rejected: ${deployment_id}"
  exit 1
fi
echo "Deployment ${deployment_id} uploaded"

# Validation and publishing usually take a few minutes; Central can stall, so give up after an hour.
for _ in $(seq 1 120); do
  sleep 30
  # Keep polling through a transient error; only Central's FAILED state or the timeout ends it.
  if ! status=$(curl --fail-with-body -sS -X POST -H "${auth}" "${api}/status?id=${deployment_id}"); then
    echo "Status check failed, retrying: ${status}"
    continue
  fi
  state=$(printf '%s' "${status}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deploymentState", ""))')
  echo "Deployment ${deployment_id}: ${state}"
  case "${state}" in
    PUBLISHED) exit 0 ;;
    FAILED)
      printf '%s\n' "${status}"
      exit 1
      ;;
  esac
done
echo "Deployment ${deployment_id} did not finish within an hour; check https://central.sonatype.com/publishing/deployments"
exit 1
