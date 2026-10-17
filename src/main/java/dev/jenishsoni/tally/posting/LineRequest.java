package dev.jenishsoni.tally.posting;

import java.util.UUID;

import dev.jenishsoni.tally.common.Money;
import dev.jenishsoni.tally.common.NonZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** One side of an entry: a positive amount adds to the account, a negative amount takes from it. */
public record LineRequest(
        @NotNull UUID accountId,
        @NotNull @NonZero Long amount,
        @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN, message = "must be 3 uppercase letters") String currency) {
}
