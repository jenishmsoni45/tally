package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A general journal entry. The lines must sum to zero in every currency. The line limit
 * keeps one posting's lock set and balance-trigger work small.
 */
public record EntryRequest(
        @Size(max = 500) String description,
        Instant effectiveAt,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid LineRequest> lines) {
}
