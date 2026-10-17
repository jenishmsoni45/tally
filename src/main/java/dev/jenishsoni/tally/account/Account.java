package dev.jenishsoni.tally.account;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String code, String currency, boolean allowNegative, Instant createdAt) {
}
