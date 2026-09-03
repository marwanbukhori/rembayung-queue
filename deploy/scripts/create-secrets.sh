#!/usr/bin/env bash
# Create the wallet and credential secrets from local files.
#
# These are created imperatively and never committed. The deployment is
# therefore not fully reproducible from the repository alone, which is the
# correct trade: a wallet in git would be worse.
set -euo pipefail

WALLET_DIR="${WALLET_DIR:-$HOME/rembayung-wallet}"
DB_USER="${DB_USER:-booking}"
DB_PASSWORD="${DB_PASSWORD:?set DB_PASSWORD}"

[ -f "${WALLET_DIR}/tnsnames.ora" ] || {
  echo "no wallet at ${WALLET_DIR}" >&2; exit 1; }

echo "creating oracle-wallet from ${WALLET_DIR}"
oc create secret generic oracle-wallet \
  --from-file="${WALLET_DIR}" \
  --dry-run=client -o yaml | oc apply -f -

echo "creating oracle-credentials"
oc create secret generic oracle-credentials \
  --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USER}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  --dry-run=client -o yaml | oc apply -f -

oc get secret oracle-wallet oracle-credentials
