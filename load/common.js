// What load/transfers.js and load/hot-account.js share: the run's settings, the figures they report, a fixed hash
// for picking accounts, and one transfer. An iteration's key and body come from its number alone, so every run of
// a scenario sends the same requests.
import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Prefixes every key and account code, so two runs never share one, even on the same database.
export const RUN_ID = required('RUN_ID');
export const CURRENCY = 'CAD';

// p(99) is not in k6's default list.
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

// Both scenarios ramp the same way; each VU sends its next transfer as soon as the last one is answered.
export const RAMP_TO_50 = {
  executor: 'ramping-vus',
  startVUs: 0,
  stages: [
    { duration: '1m', target: 50 },
    { duration: '3m', target: 50 },
  ],
};

// setup() sends the seeding requests, so the figures are read from the scenario's sub-metrics, which hold the
// transfers only. k6 reports a sub-metric only when it has a threshold, hence the two that always hold.
export function thresholdsFor(scenario) {
  return {
    [`http_req_failed{scenario:${scenario}}`]: ['rate<0.01'],
    [`http_req_duration{scenario:${scenario}}`]: ['max>=0'],
    [`http_reqs{scenario:${scenario}}`]: ['count>=0'],
  };
}

// A 32-bit integer hash (xor-shift and multiply): neighbouring iteration numbers map to unrelated values, so the
// transfers in flight at one time pick unrelated accounts rather than neighbouring ones.
export function mix(n) {
  let x = n >>> 0;
  x ^= x >>> 16;
  x = Math.imul(x, 0x7feb352d);
  x ^= x >>> 15;
  x = Math.imul(x, 0x846ca68b);
  x ^= x >>> 16;
  return x >>> 0;
}

export function transfer(n, fromAccountId, toAccountId) {
  const body = JSON.stringify({ fromAccountId, toAccountId, amount: 1 + (n % 100), currency: CURRENCY });
  const res = http.post(`${BASE_URL}/transfers`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${RUN_ID}-${n}` },
    // Far above a normal posting, and above the app's 2 s pool wait plus 5 s transaction timeout, so a stuck request
    // counts as failed after 10 s instead of holding its VU for k6's default of 60 s.
    timeout: '10s',
  });
  check(res, { 'transfer answered 201': (r) => r.status === 201 });
}

function required(name) {
  if (!__ENV[name]) {
    throw new Error(`Set ${name} with -e ${name}=...`);
  }
  return __ENV[name];
}
