import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const bookingsCreated = new Counter('bookings_created');
const bookingsRejected = new Counter('bookings_rejected');

// Models the 21:00 drop: every virtual user arrives in the same instant,
// rather than ramping. A ramp would be testing a spike that does not exist.
//
// VUs and duration are tunable; defaults are 5000 over 5 minutes.
//
// A 5000-VU run over 2 minutes served all ~4989 joins with zero application
// errors, but filled only 146 of 250 seats. Two candidate causes — the shared
// sandbox ingress dropping return traffic, or simply running out of time, since
// each VU does join then poll then book sequentially. Raising the duration
// distinguishes them: if bookings rise it was time, if they hold it is capacity.
//
// That run also booked 73 seats while reporting 28, because k6 counts responses
// and the database counts commits. A dropped response after a committed
// transaction reads as a failure but is not one.
//
// Treat the SQL reconciliation as authoritative:
//   SELECT capacity, seats_taken FROM booking.slots;
//   SELECT COUNT(*) FROM booking.slots WHERE seats_taken > capacity;  -- must be 0
export const options = {
  scenarios: {
    drop: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 5000),
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '5m',
    },
  },
  thresholds: {
    // A run in which nothing was ever booked means admission is broken,
    // even if every response was a well-formed rejection.
    bookings_created: ['count>50'],
    // Only server errors count as failures. A 250-seat slot can satisfy about
    // 125 of 5000 contenders, so ~97% of responses are 403 (not admitted) or
    // 409 (sold out) — those are the system working, not failing. Counting
    // them would make a healthy run report ~89% failure.
    http_req_failed: ['rate<0.01'],
  },
};

// Treat everything below 500 as an expected outcome, so http_req_failed
// tracks server errors rather than legitimate rejections.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

const GATE = __ENV.GATE || 'http://localhost:8080';
const SLOT_ID = __ENV.SLOT_ID || 1;

export default function () {
  const join = http.post(`${GATE}/queue`);
  check(join, { 'join answered': (r) => r.status === 200 || r.status === 409 });
  if (join.status !== 200) return;               // SOLD_OUT is a valid outcome

  const token = join.json('token');

  for (let i = 0; i < 30; i++) {
    const poll = http.get(`${GATE}/queue/${token}`);
    if (poll.status === 200 && poll.json('admitted') === true) break;
  }

  const booking = http.post(
    `${GATE}/bookings`,
    JSON.stringify({
      slotId: Number(SLOT_ID),
      phone: `+6012${__VU}`,
      partySize: 2,
      idempotencyKey: `k6-${__VU}`,
    }),
    { headers: { 'Content-Type': 'application/json', 'X-Admission-Token': token } },
  );

  check(booking, {
    'booking resolved cleanly': (r) => [201, 403, 409].includes(r.status),
    'never a server error': (r) => r.status < 500,
  });

  if (booking.status === 201) {
    bookingsCreated.add(1);
  } else {
    bookingsRejected.add(1);
  }
}
