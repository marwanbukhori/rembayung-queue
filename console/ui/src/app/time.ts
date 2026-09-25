const WITH_SECONDS = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kuala_Lumpur', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
});
const WITHOUT_SECONDS = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kuala_Lumpur', hour: '2-digit', minute: '2-digit', hour12: false
});

/** Shown once per section that shows times. */
export const TIME_ZONE_LABEL = 'GMT+8';

/**
 * Every time on the site, in Malaysia time. The audience and the 21:00 drop
 * are there, and a reader abroad should see the same clock as the logs and the
 * people queueing - not their browser's.
 *
 * Accepts ISO strings (the kubelet's nanosecond ones are trimmed to what Date
 * parses), epoch milliseconds, or a Date.
 */
export function malaysiaTime(at: string | number | Date | null | undefined, withSeconds = true): string {
  if (at === null || at === undefined) {
    return '—';
  }
  const d = typeof at === 'string'
    ? new Date(at.length > 24 && at.endsWith('Z') ? at.slice(0, 23) + 'Z' : at)
    : at instanceof Date ? at : new Date(at);
  return isNaN(d.getTime()) ? '—' : (withSeconds ? WITH_SECONDS : WITHOUT_SECONDS).format(d);
}
