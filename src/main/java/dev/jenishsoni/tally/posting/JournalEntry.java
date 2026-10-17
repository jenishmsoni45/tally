package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A saved entry as returned by the posting endpoints; kind is transfer, entry or reversal. */
public record JournalEntry(
        UUID id,
        String kind,
        UUID reversesId,
        String description,
        Instant effectiveAt,
        Instant createdAt,
        List<JournalLine> lines) {
}
