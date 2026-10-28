package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A saved entry as returned by the posting endpoints; kind is transfer, entry or reversal, and riskCheck
 * with decisionId is what the risk check decided for it (see {@link EntryRisk}).
 */
public record JournalEntry(
        UUID id,
        String kind,
        UUID reversesId,
        String description,
        Instant effectiveAt,
        Instant createdAt,
        UUID decisionId,
        String riskCheck,
        List<JournalLine> lines) {
}
