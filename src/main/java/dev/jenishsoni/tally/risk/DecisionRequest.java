package dev.jenishsoni.tally.risk;

import java.time.Instant;
import java.util.UUID;

/**
 * The body tally sends the risk engine, with exactly the fields of docs/risk-decision-call.md 3.1. requestId is the
 * transfer's Idempotency-Key as received, and requestedAt is tally's clock just before the call, as of which the
 * risk engine computes its features.
 */
record DecisionRequest(String requestId, UUID fromAccountId, UUID toAccountId, long amount, String currency,
        Instant requestedAt) {
}
