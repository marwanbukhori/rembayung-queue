#!/usr/bin/env bash
# Prove the cluster can open a TCP connection to the Autonomous Database
# before any application manifest depends on it. Sandbox egress is not
# guaranteed, and a connection refused here is far cheaper to find now than
# after booking-service is deployed and failing its readiness probe.
set -euo pipefail

HOST="${1:?usage: check-egress.sh <adb-host> [port]}"
PORT="${2:-1522}"

echo "testing egress from the cluster to ${HOST}:${PORT}"
oc run egress-check-$$ \
  --image=registry.access.redhat.com/ubi9/ubi-minimal:latest \
  --restart=Never --rm -i --quiet --command -- \
  timeout 15 bash -c "</dev/tcp/${HOST}/${PORT} && echo REACHABLE || echo UNREACHABLE"
