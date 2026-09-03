import http from 'k6/http';
import { check } from 'k6';

// Models the 21:00 drop: every virtual user arrives in the same instant,
// rather than ramping. A ramp would be testing a spike that does not exist.
export const options = {
  scenarios: {
    drop: {
      executor: 'per-vu-iterations',
      vus: 5000,
      iterations: 1,
      maxDuration: '2m',
    },
  },
};

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
}
