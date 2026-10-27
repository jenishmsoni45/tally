// The seed step of the load scenarios. Accounts are created and funded through the API, as a client would do it, so
// the opening balances are ordinary journal entries and every balance equals the sum of its lines from the start.
import http from 'k6/http';
import { BASE_URL, CURRENCY, RUN_ID } from './common.js';

// Cents. An account pays at most 100 cents a transfer, so this lasts far longer than any run, and a refusal for
// missing funds never shows up as a failed request.
const OPENING_BALANCE = 10_000_000;
// An entry holds at most 100 lines: one from the funding account and 99 to new accounts.
const ACCOUNTS_PER_ENTRY = 99;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Returns the new accounts' ids. Throwing stops the test before any transfer is sent.
export function seedAccounts(count) {
  const started = Date.now();
  const [funding] = createAccounts([`${RUN_ID}-funding`], true);
  const codes = Array.from({ length: count }, (_, i) => `${RUN_ID}-${i}`);
  const ids = createAccounts(codes, false);
  for (let start = 0; start < count; start += ACCOUNTS_PER_ENTRY) {
    fund(funding, ids.slice(start, start + ACCOUNTS_PER_ENTRY), `${RUN_ID}-fund-${start}`);
  }
  // k6 divides each count by the whole run, seeding included, so its length is shown with the results.
  console.log(`Seeded ${count} accounts with ${OPENING_BALANCE} cents each in ${Date.now() - started} ms`);
  return ids;
}

// One batch, so the requests go out a few at a time (k6's batchPerHost) and the seeding stays short.
function createAccounts(codes, allowNegative) {
  const responses = http.batch(codes.map((code) => ({
    method: 'POST',
    url: `${BASE_URL}/accounts`,
    body: JSON.stringify({ code, currency: CURRENCY, allowNegative }),
    params: { headers: JSON_HEADERS },
  })));
  return responses.map((res) => requireCreated(res, 'account').json().id);
}

// The funding account may go below zero; it is where the opening balances come from.
function fund(fundingId, accountIds, key) {
  const lines = accountIds.map((accountId) => ({ accountId, amount: OPENING_BALANCE, currency: CURRENCY }));
  lines.push({ accountId: fundingId, amount: -OPENING_BALANCE * accountIds.length, currency: CURRENCY });
  const res = http.post(`${BASE_URL}/entries`, JSON.stringify({ description: 'Load test opening balances', lines }), {
    headers: { ...JSON_HEADERS, 'Idempotency-Key': key },
  });
  requireCreated(res, 'funding entry');
}

function requireCreated(res, what) {
  if (res.status !== 201) {
    throw new Error(`Seeding a ${what} answered ${res.status}: ${res.body}`);
  }
  return res;
}
