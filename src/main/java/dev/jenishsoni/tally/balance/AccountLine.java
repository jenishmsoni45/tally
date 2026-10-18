package dev.jenishsoni.tally.balance;

import java.time.Instant;
import java.util.UUID;

/** One journal line on an account, with the entry it belongs to. */
public record AccountLine(
        long lineId,
        UUID entryId,
        String kind,
        String description,
        Instant effectiveAt,
        long amount,
        String currency) {
}
