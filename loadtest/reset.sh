#!/bin/sh
# Reset both stores so a load test run is meaningful.
#
# queue:ticket is monotonic and the gate refuses tickets past DROP_TICKET_CAP,
# so one 5000-user run exhausts the cap permanently. Without this reset every
# subsequent join correctly returns SOLD_OUT and the test measures nothing.
set -e

SLOT_ID="${SLOT_ID:-1}"

echo "flushing Redis (ticket counter and issued tokens)"
docker compose exec -T redis redis-cli FLUSHALL >/dev/null

echo "releasing seats on slot ${SLOT_ID} and clearing bookings"
docker compose exec -T oracle sqlplus -S -L booking/booking@//localhost:1521/FREEPDB1 <<SQL >/dev/null
DELETE FROM bookings;
UPDATE slots SET seats_taken = 0 WHERE id = ${SLOT_ID};
COMMIT;
EXIT
SQL

echo "reset complete — slot ${SLOT_ID} is empty and the ticket counter is zero"
