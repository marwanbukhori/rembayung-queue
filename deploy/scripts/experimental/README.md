# Experimental — not working

## `dbsql.sh.wip`

An attempt to run SQL against the Autonomous Database from a throwaway pod,
borrowing the `oracle-wallet` and `oracle-credentials` Secrets already in the
cluster so no credentials need to live on a laptop.

**It does not work.** Seven attempts, each failing differently:

| Attempt | Failure |
|---|---|
| 1 | `ORA-12154` — password containing `@` parsed as the TNS alias |
| 2 | `ORA-12154` — sqlnet.ora points `WALLET_LOCATION` at `?/network/admin` |
| 3 | `ORA-12154` — `bash -lc` sources a profile that resets `TNS_ADMIN` |
| 4 | deadlock — `oc run -i` with a heredoc waits for input that never comes |
| 5 | `ORA-01017` — a quoted heredoc passed `$SPRING_DATASOURCE_PASSWORD` literally |
| 6 | bash syntax error — quoting through Python → JSON → bash |
| 7 | `ORA-28547` — Oracle Net admin error, client/server config mismatch |

Attempts 1–6 were real bugs and are fixed in the file. Attempt 7 is where it
stands: TNS resolves (`tnsping` returns OK in 730ms), TLS handshakes, but the
sqlplus client in `gvenzl/oracle-free` cannot complete a session against
Autonomous Database.

**Do not resume this without a reason.** The OCI console's SQL worksheet does
the same job in a browser, and `booking-service`'s own readiness probe already
proves the application's database connection is healthy. This was convenience,
not capability.

If it is ever worth another look, the likely fix is a proper Oracle Instant
Client image rather than the server image, which ships a client not intended
for outbound ADB connections.
