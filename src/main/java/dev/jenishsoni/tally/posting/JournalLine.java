package dev.jenishsoni.tally.posting;

import java.util.UUID;

public record JournalLine(long id, UUID accountId, long amount, String currency) {
}
