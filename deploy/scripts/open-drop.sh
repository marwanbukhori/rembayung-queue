#!/usr/bin/env bash
# Open the drop now and restart the gate so every replica agrees on when it
# opened. The ConfigMap is read at startup, so a patch alone is not enough.
#
# Redis also has to be flushed: queue:ticket is monotonic and the cap is 250,
# so without a reset every join in a second run correctly returns SOLD_OUT and
# the demo shows nothing.
set -euo pipefail

# Open the drop at NOW, not in the past.
#
# Backdating over-admits: admittedBy(now) = rate x elapsed, so opening 30s ago
# at 8/s admits 240 of the 250 tickets instantly and the admit rate throttles
# nothing. Everyone then books at once and exhausts the connection pool. That
# offset was harmless at 200/s, where everyone is admitted within a second
# regardless; at a calibrated rate it defeats the entire mechanism.
OPENS_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "opening the drop at ${OPENS_AT}"
oc patch configmap queue-gate-config --type merge \
  -p "{\"data\":{\"DROP_OPENS_AT\":\"${OPENS_AT}\"}}"

echo "flushing the ticket counter"
oc exec deploy/redis -- redis-cli FLUSHALL

echo "restarting the gate so the new window is picked up"
oc rollout restart deploy/queue-gate
oc rollout status deploy/queue-gate --timeout=180s

echo "drop open; seats must be reset separately in the database"
