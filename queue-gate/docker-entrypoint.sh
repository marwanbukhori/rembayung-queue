#!/bin/sh
# The admission window is measured from a ticket's turn, which is relative to
# the drop opening. A fixed past timestamp therefore expires every token once
# more than one window has elapsed. For local runs, open the drop just before
# startup so tickets are admitted immediately with a full window ahead of them.
if [ -z "$DROP_OPENS_AT" ]; then
  DROP_OPENS_AT="$(date -u -d '30 seconds ago' +%Y-%m-%dT%H:%M:%SZ)"
  export DROP_OPENS_AT
  echo "queue-gate: drop window opened at $DROP_OPENS_AT"
fi
exec java -jar /app/app.jar
