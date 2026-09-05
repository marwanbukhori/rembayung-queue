import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const bookingsCreated = new Counter('bookings_created');
const bookingsRejected = new Counter('bookings_rejected');

// The console's copy of loadtest/drop.js, parameterised by DROP_ID so a
// visitor's run lands in their own sandbox rather than on slot 1.
//
// Two differences from the laptop version, and both are the point of running
// it here:
//
//   1. It talks to http://queue-gate:8080 inside the cluster, so it SKIPS THE
//      PUBLIC ROUTE. What it exercises is the queue, the admission rate and
//      the seat invariant. What it does not exercise is the ingress path, and
//      the page says so rather than letting a clean run imply otherwise.
//
//   2. VUS defaults to 200, not 5000. 200 is the measured ceiling of
//      usefulness, not a resource compromise — the Phase 3 ladder showed the
//      sandbox router shedding connections above it:
//
//          offered   arrived   failed
//             200       200       0%
//            1000       662      75%
//            3000       818      92%
//
//      Higher counts are offered rather than forbidden, because hiding the
//      option would hide the finding. They are labelled as edge shedding so a
//      third of the traffic never arriving does not read as the application
//      failing.
export const options = {
  scenarios: {
    drop: {
      // Every virtual user arrives in the same instant, as they do at 21:00.
      // A ramp would be testing a spike that does not happen.
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '4m',
    },
  },
  // No thresholds here, unlike the laptop script. A run that books nothing is
  // a finding the console should draw, not an exit code nobody will read: the
  // Job's own success tells a visitor almost nothing, and the queue numbers
  // on the page tell them everything.
};

// Everything below 500 is an expected outcome, so http_req_failed tracks
// server errors rather than legitimate rejections. A 403 (not admitted yet) or
// a 409 (sold out) is the system working.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

const GATE = __ENV.GATE || 'http://queue-gate:8080';
const DROP_ID = __ENV.DROP_ID || 'default';
const SLOT_ID = __ENV.SLOT_ID || 1;

export default function () {
  const join = http.post(`${GATE}/queue?drop=${DROP_ID}`);
  check(join, { 'join answered': (r) => r.status === 200 || r.status === 409 });
  if (join.status !== 200) return;               // SOLD_OUT is a valid outcome

  const token = join.json('token');

  // Poll once a second, as a real client would, for long enough to outlast the
  // queue. A tight spin covers only a few seconds; at a realistic admit rate
  // the later tickets are not admitted by then, so they would book unadmitted
  // and take a 403 that says nothing about the system.
  const pollSeconds = Number(__ENV.POLL_SECONDS || 90);
  for (let i = 0; i < pollSeconds; i++) {
    const poll = http.get(`${GATE}/queue/${token}`);
    if (poll.status === 200 && poll.json('admitted') === true) break;
    if (poll.status === 404) break;              // token expired or unknown
    sleep(1);
  }

  const booking = http.post(
    `${GATE}/bookings`,
    JSON.stringify({
      slotId: Number(SLOT_ID),
      phone: `+6012${__VU}`,
      partySize: 2,
      idempotencyKey: `${DROP_ID}-${__VU}`,
    }),
    { headers: { 'Content-Type': 'application/json', 'X-Admission-Token': token } },
  );

  check(booking, {
    'booking resolved cleanly': (r) => [201, 403, 409].includes(r.status),
    // 503 is not a fault. With admission at 200/s the fixed-size connection
    // pool saturates and the service fails fast with 503 + Retry-After rather
    // than holding a connection and then lying with a 500. That is
    // back-pressure working, and it is what a visitor is meant to see while
    // oversold stays at zero. A 500, 502 or 504 is a real failure.
    'no unhandled server fault': (r) => r.status < 500 || r.status === 503,
  });

  if (booking.status === 201) {
    bookingsCreated.add(1);
  } else {
    bookingsRejected.add(1);
  }
}
