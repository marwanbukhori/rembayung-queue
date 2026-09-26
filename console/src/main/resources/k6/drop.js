import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const bookingsCreated = new Counter('bookings_created');
const bookingsRejected = new Counter('bookings_rejected');

// The customer's path, for the run agent's funnel: two steps everyone may
// pass, then exactly one outcome each, so the outcomes add up to the VUs.
const joined = new Counter('joined');
const admitted = new Counter('admitted');
const outcome = {
  soldOutAtJoin: new Counter('outcome_sold_out_at_join'),
  gaveUp: new Counter('outcome_gave_up'),
  refusedAfterAdmission: new Counter('outcome_refused_after_admission'),
  soldOut: new Counter('outcome_sold_out'),
  overloaded: new Counter('outcome_overloaded'),
  faults: new Counter('outcome_faults'),
};
const queueWait = new Trend('queue_wait');   // seconds from joining to admission
const PARTY_SIZE = 2;
const PATIENCE_SECONDS = Number(__ENV.POLL_SECONDS || 90);

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
  if (join.status === 409) {                     // SOLD_OUT is a valid outcome
    outcome.soldOutAtJoin.add(1);
    return;
  }
  if (join.status !== 200) {
    outcome.faults.add(1);
    return;
  }
  joined.add(1);
  const joinedAt = Date.now();
  let isAdmitted = false;

  const token = join.json('token');

  // Poll once a second, as a real client would, for long enough to outlast the
  // queue. A tight spin covers only a few seconds; at a realistic admit rate
  // the later tickets are not admitted by then, so they would book unadmitted
  // and take a 403 that says nothing about the system.
  for (let i = 0; i < PATIENCE_SECONDS; i++) {
    const poll = http.get(`${GATE}/queue/${token}`);
    if (poll.status === 200 && poll.json('admitted') === true) {
      isAdmitted = true;
      admitted.add(1);
      queueWait.add((Date.now() - joinedAt) / 1000);
      break;
    }
    if (poll.status === 404) break;              // token expired or unknown
    sleep(1);
  }

  const booking = http.post(
    `${GATE}/bookings`,
    JSON.stringify({
      slotId: Number(SLOT_ID),
      phone: `+6012${__VU}`,
      partySize: PARTY_SIZE,
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
    if (booking.status === 403) {
      (isAdmitted ? outcome.refusedAfterAdmission : outcome.gaveUp).add(1);
    } else if (booking.status === 409) {
      outcome.soldOut.add(1);
    } else if (booking.status === 503) {
      outcome.overloaded.add(1);
    } else {
      outcome.faults.add(1);
    }
  }
}

// One machine-readable line at the end, which the console's run agent reads
// instead of k6's own table: that table is for people, and its shape changes
// between k6 versions. "notClean" counts bookings that ended in anything other
// than 201, 403 or 409 - the 503s of a saturated pool, and any real fault.
export function handleSummary(data) {
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const trend = (key) => (m.http_req_duration ? Number(m.http_req_duration.values[key] || 0) : 0);
  const wait = (key) => (m.queue_wait ? Number(m.queue_wait.values[key] || 0) : 0);
  const clean = (data.root_group.checks || []).find((c) => c.name === 'booking resolved cleanly');
  const summary = {
    vus: Number(__ENV.VUS || 0),
    iterations: count('iterations'),
    booked: count('bookings_created'),
    rejected: count('bookings_rejected'),
    notClean: clean ? clean.fails : 0,
    p50: Math.round(trend('med')),
    p95: Math.round(trend('p(95)')),
    max: Math.round(trend('max')),
    durationMs: Math.round(data.state.testRunDurationMs),
    joined: count('joined'),
    admitted: count('admitted'),
    soldOutAtJoin: count('outcome_sold_out_at_join'),
    gaveUp: count('outcome_gave_up'),
    refusedAfterAdmission: count('outcome_refused_after_admission'),
    soldOut: count('outcome_sold_out'),
    overloaded: count('outcome_overloaded'),
    faults: count('outcome_faults'),
    queueWaitP50: Math.round(wait('med')),
    queueWaitP95: Math.round(wait('p(95)')),
    queueWaitMax: Math.round(wait('max')),
    partySize: PARTY_SIZE,
    patienceSeconds: PATIENCE_SECONDS,
  };
  return {
    stdout: `booked ${summary.booked}, rejected ${summary.rejected}, not clean ${summary.notClean}, `
      + `p95 ${summary.p95} ms\nK6_SUMMARY ${JSON.stringify(summary)}\n`,
  };
}
