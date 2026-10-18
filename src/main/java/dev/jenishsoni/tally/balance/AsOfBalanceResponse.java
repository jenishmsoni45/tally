package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.util.UUID;

/** The balance in cents the account had at {@code asOf}, summed from its lines rather than read from the stored balance. */
public record AsOfBalanceResponse(UUID accountId, long balance, String currency, Instant asOf) {
}
