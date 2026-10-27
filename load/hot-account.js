// Scenario B, run by scripts/load.sh: the same load as load/transfers.js, except that every transfer pays one
// account, the first seeded. Every posting then takes that account's balance row lock, so postings queue behind one
// another, and that wait is what this scenario is for. An iteration's payer, amount and key come from its number alone.
import exec from 'k6/execution';
import { SUMMARY_TREND_STATS, RAMP_TO_50, mix, thresholdsFor, transfer } from './common.js';
import { seedAccounts } from './seed.js';

const ACCOUNTS = 1000;

export const options = {
  scenarios: {
    hot_account: RAMP_TO_50,
  },
  thresholds: thresholdsFor('hot_account'),
  summaryTrendStats: SUMMARY_TREND_STATS,
};

export function setup() {
  return { accounts: seedAccounts(ACCOUNTS) };
}

export default function ({ accounts }) {
  const n = exec.scenario.iterationInTest;
  // Never index 0: the app refuses a transfer from an account to itself.
  const payer = 1 + (mix(n) % (ACCOUNTS - 1));
  transfer(n, accounts[payer], accounts[0]);
}
