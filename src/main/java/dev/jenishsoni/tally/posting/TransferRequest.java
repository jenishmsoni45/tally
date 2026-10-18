package dev.jenishsoni.tally.posting;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import dev.jenishsoni.tally.common.Money;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Moves {@code amount} cents from one account to another. A missing effectiveAt means now. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Positive Long amount,
        @NotBlank @Pattern(regexp = Money.CURRENCY_PATTERN, message = "must be 3 uppercase letters") String currency,
        @Size(max = 500) String description,
        Instant effectiveAt) {

    /** A transfer to the same account would be a pair of lines that cancel out. */
    @JsonIgnore
    @AssertTrue(message = "must differ from fromAccountId")
    public boolean isToAccountDifferent() {
        return fromAccountId == null || !fromAccountId.equals(toAccountId);
    }

}
