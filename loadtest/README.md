# Load test

Reproduces the 21:00 drop as a synchronized burst.

    docker compose up -d --build
    k6 run loadtest/drop.js

Environment overrides: `GATE` (default `http://localhost:8080`), `SLOT_ID` (default `1`).

The invariant to check afterwards — no application code involved:

    docker exec -it $(docker compose ps -q oracle) \
      sqlplus -S booking/booking@//localhost:1521/FREEPDB1

    SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM slots;
    SELECT COUNT(*) AS violations FROM slots WHERE seats_taken > capacity;

`violations` must be 0, and `seats_taken` must never exceed `capacity`.
