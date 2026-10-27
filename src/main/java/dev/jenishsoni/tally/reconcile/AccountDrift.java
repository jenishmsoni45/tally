package dev.jenishsoni.tally.reconcile;

import java.time.Instant;
import java.util.UUID;

/** An account whose stored balance differs from the sum of its journal lines, as one recount found it. */
public record AccountDrift(UUID accountId, long stored, long computed, Instant foundAt) {
}
