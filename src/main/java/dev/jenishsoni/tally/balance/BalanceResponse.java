package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.util.UUID;

/** The stored balance in cents; the reconciliation job checks it against the account's lines. */
public record BalanceResponse(UUID accountId, long balance, String currency, Instant updatedAt) {
}
