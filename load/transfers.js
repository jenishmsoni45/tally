// Scenario A of the load test: transfers between random pairs of the 1,000 seeded accounts. scripts/load.sh runs it
// as the 4-minute ramp and, with VUS and DURATION set, as one step of the saturation run. An iteration's accounts,
// amount and key come from its number alone.
import exec from 'k6/execution';
import { SUMMARY_TREND_STATS, RAMP_TO_50, mix, thresholdsFor, transfer } from './common.js';
import { seedAccounts } from './seed.js';

const ACCOUNTS = 1000;

export const options = {
  scenarios: {
    transfers: __ENV.VUS ? saturationStep() : RAMP_TO_50,
  },
  thresholds: thresholdsFor('transfers'),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS) };
}

export default function ({ accounts }) {
  const n = exec.scenario.iterationInTest;
  const h = mix(n);
  const fromIndex = h % ACCOUNTS;
  // An offset of 1 to 999 reaches every other account and never the payer itself.
  const toIndex = (fromIndex + 1 + (Math.floor(h / ACCOUNTS) % (ACCOUNTS - 1))) % ACCOUNTS;
  transfer(n, accounts[fromIndex], accounts[toIndex]);
}

function saturationStep() {
  if (!__ENV.DURATION) {
    throw new Error('Set DURATION with -e DURATION=... when VUS is set');
  }
  return { executor: 'constant-vus', vus: Number(__ENV.VUS), duration: __ENV.DURATION };
}
