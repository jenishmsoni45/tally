// Transfers for scripts/crash-test.sh. An iteration's key and body come from its number alone, so a second run
// of the same iterations sends the same requests, and the server must answer the ones it already posted from
// their saved responses instead of moving the money again.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL;
const RUN_ID = __ENV.RUN_ID;
const ACCOUNT_A = __ENV.ACCOUNT_A;
const ACCOUNT_B = __ENV.ACCOUNT_B;

export const options = {
  scenarios: {
    transfers: {
      // Shared iterations hand out each iteration number once across all VUs, so both runs cover the same set.
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS),
      iterations: Number(__ENV.ITERATIONS),
      maxDuration: '10m',
    },
  },
  // Holds only for the replay run; the run that is killed is started with --no-thresholds.
  thresholds: {
    checks: ['rate==1'],
  },
};

export default function () {
  const n = exec.scenario.iterationInTest;
  // Alternating the direction keeps both balances small.
  const [from, to] = n % 2 === 0 ? [ACCOUNT_A, ACCOUNT_B] : [ACCOUNT_B, ACCOUNT_A];
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount: 1 + (n % 100), currency: 'CAD' });
  const res = http.post(`${BASE_URL}/transfers`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `crash-${RUN_ID}-${n}` },
    // Far above a normal posting, so only a dead app fails a request.
    timeout: '10s',
  });
  check(res, { 'transfer answered 201': (r) => r.status === 201 });
}
